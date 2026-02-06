package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepoMetadata;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.domain.ingestion.SourceFileLanguage;
import com.williamcallahan.javachat.domain.ingestion.SourceFileProcessingResult;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Processes source code files from a GitHub repository into chunked hybrid vectors.
 *
 * <p>The processor performs file validation, changed-file detection, strict stale-vector pruning,
 * chunk generation, metadata enrichment, and Qdrant upsert into a repository collection.</p>
 */
@Service
public class SourceCodeFileIngestionProcessor {
    private static final Logger log = LoggerFactory.getLogger(SourceCodeFileIngestionProcessor.class);

    /** Maximum file size (1 MiB) accepted for source code ingestion. */
    public static final long MAX_FILE_SIZE_BYTES = 1_048_576;

    private static final String DOCUMENT_SET_PREFIX = "github/";
    private static final Pattern JAVA_PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final char NEWLINE_CHARACTER = '\n';
    private static final char CARRIAGE_RETURN_CHARACTER = '\r';
    private static final char TAB_CHARACTER = '\t';

    private final ChunkProcessingService chunkProcessingService;
    private final HybridVectorService hybridVectorService;
    private final LocalStoreService localStoreService;
    private final ProgressTracker progressTracker;
    private final IngestedFilePruneService ingestedFilePruneService;

    /**
     * Creates a source-code ingestion processor with required storage dependencies.
     */
    public SourceCodeFileIngestionProcessor(
            ChunkProcessingService chunkProcessingService,
            HybridVectorService hybridVectorService,
            LocalStoreService localStoreService,
            ProgressTracker progressTracker,
            IngestedFilePruneService ingestedFilePruneService) {
        this.chunkProcessingService = Objects.requireNonNull(chunkProcessingService, "chunkProcessingService");
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
        this.progressTracker = Objects.requireNonNull(progressTracker, "progressTracker");
        this.ingestedFilePruneService = Objects.requireNonNull(ingestedFilePruneService, "ingestedFilePruneService");
    }

    /**
     * Holds immutable file context values derived after validation.
     */
    private record ValidatedFileContext(
            String fileName,
            long fileSizeBytes,
            long lastModifiedMillis,
            String relativePath,
            String sourceUrl,
            String sourceLanguage,
            String documentType) {}

    /**
     * Holds UTF-8 file text and its deterministic content fingerprint.
     */
    private record ReadableFileContent(String text, String contentFingerprint) {
        private ReadableFileContent {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(contentFingerprint, "contentFingerprint");
        }
    }

    /**
     * Processes a single repository file and returns the outcome paired with the file URL.
     *
     * <p>The returned {@link SourceFileProcessingResult} includes the authoritative file URL
     * regardless of outcome, enabling callers to build a complete set of active file URLs
     * for deleted-file detection.</p>
     */
    public SourceFileProcessingResult process(
            Path repositoryRoot, Path sourceFilePath, GitHubRepoMetadata repositoryMetadata, String collectionName) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(sourceFilePath, "sourceFilePath");
        Objects.requireNonNull(repositoryMetadata, "repositoryMetadata");
        Objects.requireNonNull(collectionName, "collectionName");

        String relativePath =
                repositoryRoot.relativize(sourceFilePath).toString().replace('\\', '/');
        String fileUrl = buildFileUrl(repositoryMetadata, relativePath);

        long fileStartMillis = System.currentTimeMillis();

        Optional<LocalDocsFileOutcome> validationFailure = validateFileAttributes(sourceFilePath);
        if (validationFailure.isPresent()) {
            return new SourceFileProcessingResult(validationFailure.get(), fileUrl);
        }

        ValidatedFileContext fileContext = buildFileContext(sourceFilePath, repositoryRoot, repositoryMetadata);
        Optional<ReadableFileContent> readableContent = readAndFingerprintFile(sourceFilePath, fileContext);
        if (readableContent.isEmpty()) {
            return new SourceFileProcessingResult(LocalDocsFileOutcome.skippedFile(), fileUrl);
        }
        ReadableFileContent fileContent = readableContent.get();

        LocalStoreService.FileIngestionRecord previousFileRecord = localStoreService
                .readFileIngestionRecord(fileContext.sourceUrl())
                .orElse(null);

        boolean unchangedByFingerprint = isUnchangedByFingerprint(previousFileRecord, fileContext, fileContent);
        if (unchangedByFingerprint
                && hasSufficientStoredPointCoverage(previousFileRecord, collectionName, fileContext)) {
            log.debug("Skipping unchanged file (already ingested): {}", fileContext.relativePath());
            return new SourceFileProcessingResult(LocalDocsFileOutcome.skippedFile(), fileUrl);
        }
        if (unchangedByFingerprint) {
            log.info(
                    "File marker exists but collection has missing points for URL; forcing reindex: {}",
                    fileContext.relativePath());
        }

        boolean requiresFullReindex = previousFileRecord != null && !unchangedByFingerprint;
        if (requiresFullReindex) {
            try {
                ingestedFilePruneService.pruneCollectionFileStrict(
                        collectionName, fileContext.sourceUrl(), previousFileRecord);
            } catch (IOException pruneException) {
                return new SourceFileProcessingResult(
                        LocalDocsFileOutcome.failedFile(new IngestionLocalFailure(
                                sourceFilePath.toString(), "prune", pruneException.getMessage())),
                        fileUrl);
            }
        }

        LocalDocsFileOutcome chunkOutcome = chunkAndUpsert(
                sourceFilePath,
                fileContext,
                fileContent,
                repositoryMetadata,
                collectionName,
                requiresFullReindex,
                fileStartMillis);
        return new SourceFileProcessingResult(chunkOutcome, fileUrl);
    }

    private Optional<LocalDocsFileOutcome> validateFileAttributes(Path sourceFilePath) {
        Path fileNamePath = sourceFilePath.getFileName();
        if (fileNamePath == null) {
            return Optional.of(LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(sourceFilePath.toString(), "filename", "Missing filename")));
        }

        try {
            long fileSizeBytes = Files.size(sourceFilePath);
            if (fileSizeBytes > MAX_FILE_SIZE_BYTES) {
                log.debug(
                        "Skipping oversized file ({}B > {}B): {}", fileSizeBytes, MAX_FILE_SIZE_BYTES, sourceFilePath);
                return Optional.of(LocalDocsFileOutcome.skippedFile());
            }
        } catch (IOException fileAttributeException) {
            return Optional.of(LocalDocsFileOutcome.failedFile(new IngestionLocalFailure(
                    sourceFilePath.toString(), "file-attributes", fileAttributeException.getMessage())));
        }
        return Optional.empty();
    }

    private ValidatedFileContext buildFileContext(
            Path sourceFilePath, Path repositoryRoot, GitHubRepoMetadata repositoryMetadata) {
        String fileName = sourceFilePath.getFileName().toString();
        long fileSizeBytes;
        long lastModifiedMillis;
        try {
            fileSizeBytes = Files.size(sourceFilePath);
            lastModifiedMillis = Files.getLastModifiedTime(sourceFilePath).toMillis();
        } catch (IOException fileAttributeException) {
            throw new IllegalStateException(
                    "File attributes unreadable after validation: " + sourceFilePath, fileAttributeException);
        }

        String relativePath =
                repositoryRoot.relativize(sourceFilePath).toString().replace('\\', '/');
        String sourceUrl = buildFileUrl(repositoryMetadata, relativePath);
        String sourceLanguage = SourceFileLanguage.fromFileName(fileName);
        String documentType = SourceFileLanguage.classifyDocType(fileName);

        return new ValidatedFileContext(
                fileName, fileSizeBytes, lastModifiedMillis, relativePath, sourceUrl, sourceLanguage, documentType);
    }

    private Optional<ReadableFileContent> readAndFingerprintFile(
            Path sourceFilePath, ValidatedFileContext fileContext) {
        String rawFileText;
        try {
            rawFileText = Files.readString(sourceFilePath, StandardCharsets.UTF_8);
        } catch (MalformedInputException malformedInputException) {
            log.debug("Skipping binary file (UTF-8 decode failed): {}", fileContext.relativePath());
            return Optional.empty();
        } catch (IOException readException) {
            throw new IllegalStateException("Failed reading file after validation: " + sourceFilePath, readException);
        }

        String fileText = sanitizeControlCharacters(rawFileText, fileContext.relativePath());
        if (fileText.isBlank()) {
            log.debug("Skipping empty file: {}", fileContext.relativePath());
            return Optional.empty();
        }

        try {
            String contentFingerprint = localStoreService.computeFileContentFingerprint(sourceFilePath);
            return Optional.of(new ReadableFileContent(fileText, contentFingerprint));
        } catch (IOException fingerprintException) {
            throw new IllegalStateException(
                    "Failed computing file fingerprint after successful read: " + sourceFilePath, fingerprintException);
        }
    }

    private static String sanitizeControlCharacters(String rawText, String relativePath) {
        StringBuilder sanitizedTextBuilder = new StringBuilder(rawText.length());
        int removedControlCharacterCount = 0;
        for (int characterIndex = 0; characterIndex < rawText.length(); characterIndex++) {
            char candidateCharacter = rawText.charAt(characterIndex);
            if (isAllowedControlCharacter(candidateCharacter) || !Character.isISOControl(candidateCharacter)) {
                sanitizedTextBuilder.append(candidateCharacter);
            } else {
                removedControlCharacterCount++;
            }
        }
        if (removedControlCharacterCount > 0) {
            log.debug(
                    "Removed {} unsupported control character(s) before embedding: {}",
                    removedControlCharacterCount,
                    relativePath);
        }
        return sanitizedTextBuilder.toString();
    }

    private static boolean isAllowedControlCharacter(char candidateCharacter) {
        return candidateCharacter == NEWLINE_CHARACTER
                || candidateCharacter == CARRIAGE_RETURN_CHARACTER
                || candidateCharacter == TAB_CHARACTER;
    }

    private boolean isUnchangedByFingerprint(
            LocalStoreService.FileIngestionRecord previousFileRecord,
            ValidatedFileContext fileContext,
            ReadableFileContent fileContent) {
        if (previousFileRecord == null) {
            return false;
        }
        return previousFileRecord.fileSizeBytes() == fileContext.fileSizeBytes()
                && previousFileRecord.lastModifiedMillis() == fileContext.lastModifiedMillis()
                && fileContent.contentFingerprint().equals(previousFileRecord.contentFingerprint());
    }

    private boolean hasSufficientStoredPointCoverage(
            LocalStoreService.FileIngestionRecord previousFileRecord,
            String collectionName,
            ValidatedFileContext fileContext) {
        long storedPointCount = hybridVectorService.countPointsForUrl(collectionName, fileContext.sourceUrl());
        int expectedChunkCount = 0;
        if (previousFileRecord != null && previousFileRecord.chunkHashes() != null) {
            expectedChunkCount = previousFileRecord.chunkHashes().size();
        }
        if (storedPointCount <= 0) {
            return false;
        }
        return expectedChunkCount <= 0 || storedPointCount >= expectedChunkCount;
    }

    private LocalDocsFileOutcome chunkAndUpsert(
            Path sourceFilePath,
            ValidatedFileContext fileContext,
            ReadableFileContent fileContent,
            GitHubRepoMetadata repositoryMetadata,
            String collectionName,
            boolean forceChunking,
            long fileStartMillis) {
        String packageName = extractPackageName(fileContext.fileName(), fileContent.text());
        ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome;
        try {
            chunkingOutcome = forceChunking
                    ? chunkProcessingService.processAndStoreChunksForce(
                            fileContent.text(), fileContext.sourceUrl(), fileContext.fileName(), packageName)
                    : chunkProcessingService.processAndStoreChunks(
                            fileContent.text(), fileContext.sourceUrl(), fileContext.fileName(), packageName);
        } catch (IOException chunkingException) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(sourceFilePath.toString(), "chunking", chunkingException.getMessage()));
        }

        List<Document> indexedDocuments = chunkingOutcome.documents();
        if (indexedDocuments.isEmpty()) {
            return resolveEmptyChunkOutcome(chunkingOutcome, fileContext, fileContent.contentFingerprint());
        }

        enrichMetadata(
                indexedDocuments,
                repositoryMetadata,
                fileContext.relativePath(),
                fileContext.sourceLanguage(),
                fileContext.documentType());
        hybridVectorService.upsertToCollection(collectionName, indexedDocuments);

        logProcessingComplete(
                indexedDocuments.size(), chunkingOutcome.totalChunks(), fileContext.relativePath(), fileStartMillis);

        markDocumentsIngested(indexedDocuments);
        markFileIngested(
                fileContext.sourceUrl(),
                fileContext.fileSizeBytes(),
                fileContext.lastModifiedMillis(),
                fileContent.contentFingerprint(),
                chunkingOutcome.allChunkHashes());

        return LocalDocsFileOutcome.processedFile();
    }

    private LocalDocsFileOutcome resolveEmptyChunkOutcome(
            ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome,
            ValidatedFileContext fileContext,
            String contentFingerprint) {
        if (chunkingOutcome.skippedAllChunks()) {
            log.debug("All chunks already ingested: {}", fileContext.relativePath());
            markFileIngested(
                    fileContext.sourceUrl(),
                    fileContext.fileSizeBytes(),
                    fileContext.lastModifiedMillis(),
                    contentFingerprint,
                    chunkingOutcome.allChunkHashes());
            return LocalDocsFileOutcome.skippedFile();
        }
        if (chunkingOutcome.generatedNoChunks()) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(fileContext.relativePath(), "empty-content", "No chunks generated"));
        }
        return LocalDocsFileOutcome.skippedFile();
    }

    private void logProcessingComplete(
            int indexedDocumentCount, int totalChunkCount, String relativePath, long fileStartMillis) {
        long totalDuration = System.currentTimeMillis() - fileStartMillis;
        log.info(
                "Processed {}/{} chunks for {} in {}ms ({})",
                indexedDocumentCount,
                totalChunkCount,
                relativePath,
                totalDuration,
                progressTracker.formatPercent());
    }

    private static String buildFileUrl(GitHubRepoMetadata repositoryMetadata, String relativePath) {
        String revision = repositoryMetadata.repoBranch();
        if (revision.isBlank()) {
            revision = repositoryMetadata.commitHash();
        }
        if (revision.isBlank()) {
            revision = "HEAD";
        }
        String encodedRevisionPath = encodePathWithForwardSlashes(revision);
        String encodedRelativePath = encodePathWithForwardSlashes(relativePath);
        return repositoryMetadata.repoUrl() + "/blob/" + encodedRevisionPath + "/" + encodedRelativePath;
    }

    private static String encodePathWithForwardSlashes(String rawPath) {
        String normalizedPath = rawPath == null ? "" : rawPath.replace('\\', '/');
        String[] pathSegments = normalizedPath.split("/");
        StringBuilder encodedPathBuilder = new StringBuilder();
        boolean firstSegmentWritten = false;
        for (String pathSegment : pathSegments) {
            if (pathSegment.isEmpty()) {
                continue;
            }
            if (firstSegmentWritten) {
                encodedPathBuilder.append('/');
            }
            String encodedSegment =
                    URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
            encodedPathBuilder.append(encodedSegment);
            firstSegmentWritten = true;
        }
        if (encodedPathBuilder.isEmpty()) {
            throw new IllegalArgumentException("GitHub path must contain at least one non-empty segment");
        }
        return encodedPathBuilder.toString();
    }

    private static String extractPackageName(String fileName, String fileText) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
            return "";
        }
        Matcher packageMatcher = JAVA_PACKAGE_PATTERN.matcher(fileText);
        if (packageMatcher.find()) {
            return packageMatcher.group(1);
        }
        return "";
    }

    private static void enrichMetadata(
            List<Document> indexedDocuments,
            GitHubRepoMetadata repositoryMetadata,
            String relativePath,
            String sourceLanguage,
            String documentType) {
        for (Document indexedDocument : indexedDocuments) {
            var metadata = indexedDocument.getMetadata();
            metadata.put("filePath", relativePath);
            if (!sourceLanguage.isEmpty()) {
                metadata.put("language", sourceLanguage);
            }
            metadata.put("docType", documentType);
            metadata.put("docSet", DOCUMENT_SET_PREFIX + repositoryMetadata.repoKey());
            metadata.put("sourceKind", "github");
            metadata.put("sourceName", repositoryMetadata.repoOwner());
            metadata.put("repoUrl", repositoryMetadata.repoUrl());
            metadata.put("repoOwner", repositoryMetadata.repoOwner());
            metadata.put("repoName", repositoryMetadata.repoName());
            metadata.put("repoKey", repositoryMetadata.repoKey());
            if (!repositoryMetadata.repoBranch().isBlank()) {
                metadata.put("repoBranch", repositoryMetadata.repoBranch());
            }
            if (!repositoryMetadata.commitHash().isBlank()) {
                metadata.put("commitHash", repositoryMetadata.commitHash());
            }
            if (!repositoryMetadata.license().isBlank()) {
                metadata.put("license", repositoryMetadata.license());
            }
            if (!repositoryMetadata.repoDescription().isBlank()) {
                metadata.put("repoDescription", repositoryMetadata.repoDescription());
            }
        }
    }

    private void markDocumentsIngested(List<Document> indexedDocuments) {
        for (Document indexedDocument : indexedDocuments) {
            Object hashMetadata = indexedDocument.getMetadata().get("hash");
            if (hashMetadata == null) {
                String sourceUrl =
                        Objects.toString(indexedDocument.getMetadata().get("url"), "(unknown)");
                throw new IllegalStateException(
                        "Document missing required 'hash' metadata after chunking pipeline; url=" + sourceUrl);
            }
            try {
                localStoreService.markHashIngested(hashMetadata.toString());
            } catch (IOException markerException) {
                throw new IllegalStateException("Failed to mark hash as ingested: " + hashMetadata, markerException);
            }
        }
    }

    private void markFileIngested(
            String sourceUrl,
            long fileSizeBytes,
            long lastModifiedMillis,
            String contentFingerprint,
            List<String> chunkHashes) {
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        if (sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank for file ingestion marker");
        }
        try {
            localStoreService.markFileIngested(
                    sourceUrl, fileSizeBytes, lastModifiedMillis, contentFingerprint, chunkHashes);
        } catch (IOException markerException) {
            throw new IllegalStateException("Failed to mark file as ingested: " + sourceUrl, markerException);
        }
    }
}
