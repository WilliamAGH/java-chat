package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionKind;
import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardDecision;
import com.williamcallahan.javachat.service.ingestion.IngestionProvenanceDeriver.IngestionProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Processes a single local HTML/PDF file into chunks and stores them in Qdrant.
 *
 * <p>This component owns the per-file ingestion lifecycle: provenance derivation, content extraction,
 * chunking, hybrid vector upsert, and local ingestion marker updates.</p>
 */
@Service
public class LocalDocsFileIngestionProcessor {
    private static final Logger log = LoggerFactory.getLogger(LocalDocsFileIngestionProcessor.class);
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private static final String FILE_URL_PREFIX = "file://";
    private static final String API_PATH_SEGMENT = "${app.ingestion.api-path-segment:/api/}";

    private final FileContentServices content;
    private final IngestionStorageServices storage;
    private final ProgressTracker progressTracker;
    private final IngestionProvenanceDeriver provenanceDeriver;
    private final LocalIngestionFailureFactory failureFactory;
    private final IngestedFilePruneService ingestedFilePruneService;

    /**
     * Wires grouped ingestion dependencies.
     *
     * @param content file content extraction and validation services
     * @param storage chunking, hashing, vector storage, and local marker services
     * @param progressTracker ingestion progress tracker
     * @param provenanceDeriver derives deterministic provenance tokens for routing and citations
     * @param failureFactory builds typed failures with diagnostics
     * @param ingestedFilePruneService shared stale-file prune service
     */
    public LocalDocsFileIngestionProcessor(
            FileContentServices content,
            IngestionStorageServices storage,
            ProgressTracker progressTracker,
            IngestionProvenanceDeriver provenanceDeriver,
            LocalIngestionFailureFactory failureFactory,
            IngestedFilePruneService ingestedFilePruneService) {
        this.content = Objects.requireNonNull(content, "content");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.progressTracker = Objects.requireNonNull(progressTracker, "progressTracker");
        this.provenanceDeriver = Objects.requireNonNull(provenanceDeriver, "provenanceDeriver");
        this.failureFactory = Objects.requireNonNull(failureFactory, "failureFactory");
        this.ingestedFilePruneService = Objects.requireNonNull(ingestedFilePruneService, "ingestedFilePruneService");
    }

    /**
     * Processes a single local file and returns a typed outcome.
     *
     * @param root root ingestion directory
     * @param file file to process
     * @return processing outcome indicating processed/failed/skipped
     */
    public LocalDocsFileOutcome process(Path root, Path file) {
        long fileStartMillis = System.currentTimeMillis();
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "filename", "Missing filename"));
        }

        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        String url = mapLocalPathToUrl(file);
        if (fileName.endsWith(".pdf")) {
            Optional<String> publicPdfUrl = DocsSourceRegistry.mapBookLocalToPublic(file.toString());
            url = publicPdfUrl.orElse(url);
        }

        final long fileSizeBytes;
        final long lastModifiedMillis;
        final String fileContentFingerprint;
        try {
            fileSizeBytes = Files.size(file);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
            fileContentFingerprint = storage.localStore().computeFileContentFingerprint(file);
        } catch (IOException attributeException) {
            return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "file-attributes", attributeException));
        }

        IngestionProvenance provenance = provenanceDeriver.derive(root, file, url);
        var router = storage.router();
        var localStore = storage.localStore();
        QdrantCollectionKind collectionKind =
                router.route(provenance.docSet(), provenance.docPath(), provenance.docType(), url);
        INDEXING_LOG.info(
                "[INDEXING] Routed → {} (docSet={}, docType={})",
                collectionKind,
                provenance.docSet(),
                provenance.docType());

        final Optional<LocalStoreService.FileIngestionRecord> priorIngestionRecord;
        try {
            priorIngestionRecord = localStore.readFileIngestionRecord(url);
        } catch (RuntimeException markerReadException) {
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "file-marker-read", markerReadException));
        }

        boolean unchangedByFingerprint = priorIngestionRecord
                .map(ingestionRecord -> ingestionRecord.fileSizeBytes() == fileSizeBytes
                        && ingestionRecord.lastModifiedMillis() == lastModifiedMillis
                        && fileContentFingerprint.equals(ingestionRecord.contentFingerprint()))
                .orElse(false);

        if (unchangedByFingerprint) {
            try {
                long storedPointCount = storage.hybridVector().countPointsForUrl(collectionKind, url);
                int expectedChunkCount = priorIngestionRecord
                        .map(ingestionRecord -> ingestionRecord.chunkHashes().size())
                        .orElse(0);
                boolean sufficientPointCoverage = expectedChunkCount <= 0 || storedPointCount >= expectedChunkCount;
                if (storedPointCount > 0 && sufficientPointCoverage) {
                    INDEXING_LOG.debug("[INDEXING] Skipping unchanged file (already ingested)");
                    return LocalDocsFileOutcome.skippedFile();
                }
            } catch (RuntimeException consistencyException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "qdrant-consistency-check", consistencyException));
            }
            INDEXING_LOG.warn("[INDEXING] Marker exists but Qdrant has no points for URL; forcing reindex");
        }

        boolean requiresFullReindex = priorIngestionRecord.isPresent() && !unchangedByFingerprint;
        if (requiresFullReindex) {
            try {
                prunePreviouslyIngestedFileStrict(collectionKind, url, priorIngestionRecord);
            } catch (IOException ioException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-local", ioException));
            } catch (RuntimeException runtimeException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-runtime", runtimeException));
            }
        }

        String title;
        String bodyText = "";
        String packageName;

        if (fileName.endsWith(".pdf")) {
            try {
                var pdfExtractor = content.pdfExtractor();
                var titleExtractor = content.titleExtractor();
                String metadata = pdfExtractor.getPdfMetadata(file);
                title = titleExtractor.extractTitle(metadata, fileNamePath.toString());
                packageName = "";
            } catch (IOException pdfExtractionException) {
                log.error(
                        "Failed to extract PDF content (exception type: {})",
                        pdfExtractionException.getClass().getSimpleName());
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "pdf-extraction", pdfExtractionException));
            }
        } else {
            try {
                var fileOps = content.fileOps();
                var htmlExtractor = content.htmlExtractor();
                String html = fileOps.readTextFile(file);
                org.jsoup.nodes.Document doc = Jsoup.parse(html);
                title = Optional.ofNullable(doc.title()).orElse("");
                bodyText = url.contains(API_PATH_SEGMENT)
                        ? htmlExtractor.extractJavaApiContent(doc)
                        : htmlExtractor.extractCleanContent(doc);
                packageName = JavaPackageExtractor.extractPackage(url, bodyText);
            } catch (IOException htmlReadException) {
                log.error(
                        "Failed to read HTML file (exception type: {})",
                        htmlReadException.getClass().getSimpleName());
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "html-read", htmlReadException));
            }

            var contentGuard = content.contentGuard();
            GuardDecision guardDecision = contentGuard.evaluate(bodyText);
            if (!guardDecision.acceptable()) {
                try {
                    var quarantine = content.quarantine();
                    quarantine.quarantine(file);
                    INDEXING_LOG.warn("[INDEXING] Content guard rejected file and moved it to quarantine");
                    return LocalDocsFileOutcome.failedFile(new IngestionLocalFailure(
                            file.toString(), "content-guard", "quarantined: " + guardDecision.rejectionReason()));
                } catch (IOException quarantineException) {
                    log.warn(
                            "Failed to quarantine invalid content (exception type: {})",
                            quarantineException.getClass().getSimpleName());
                    return LocalDocsFileOutcome.failedFile(
                            failureFactory.failure(file, "content-guard", quarantineException));
                }
            }
        }

        ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome;
        var chunkProcessor = storage.chunks();
        try {
            if (fileName.endsWith(".pdf")) {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName)
                        : chunkProcessor.processPdfAndStoreWithPages(file, url, title, packageName);
            } else {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processAndStoreChunksForce(bodyText, url, title, packageName)
                        : chunkProcessor.processAndStoreChunks(bodyText, url, title, packageName);
            }
        } catch (IOException chunkingException) {
            log.error(
                    "Chunking failed (exception type: {})",
                    chunkingException.getClass().getSimpleName());
            return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "chunking", chunkingException));
        }

        List<Document> documents = chunkingOutcome.documents();
        if (!documents.isEmpty()) {
            applyProvenanceMetadata(documents, provenance);
            return processDocuments(
                    collectionKind,
                    file,
                    url,
                    fileSizeBytes,
                    lastModifiedMillis,
                    fileContentFingerprint,
                    documents,
                    chunkingOutcome.allChunkHashes(),
                    fileStartMillis);
        }
        if (chunkingOutcome.skippedAllChunks()) {
            try {
                long storedPointCount = storage.hybridVector().countPointsForUrl(collectionKind, url);
                long expectedChunkCount = chunkingOutcome.allChunkHashes().size();
                if (storedPointCount < expectedChunkCount) {
                    INDEXING_LOG.warn(
                            "[INDEXING] Hash markers exist but Qdrant has insufficient points for URL; forcing reindex");
                    ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                            forceChunking(file, fileName, bodyText, url, title, packageName);
                    List<Document> forcedDocuments = forcedChunkingOutcome.documents();
                    if (!forcedDocuments.isEmpty()) {
                        applyProvenanceMetadata(forcedDocuments, provenance);
                        return processDocuments(
                                collectionKind,
                                file,
                                url,
                                fileSizeBytes,
                                lastModifiedMillis,
                                fileContentFingerprint,
                                forcedDocuments,
                                forcedChunkingOutcome.allChunkHashes(),
                                fileStartMillis);
                    }
                    if (forcedChunkingOutcome.generatedNoChunks()) {
                        return LocalDocsFileOutcome.failedFile(
                                new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
                    }
                    return LocalDocsFileOutcome.failedFile(
                            new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
                }
            } catch (IOException chunkingException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "chunking", chunkingException));
            } catch (RuntimeException consistencyException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "qdrant-consistency-check", consistencyException));
            }
            INDEXING_LOG.debug("[INDEXING] Skipping file where all chunks were previously ingested");
            markFileIngested(
                    url, fileSizeBytes, lastModifiedMillis, fileContentFingerprint, chunkingOutcome.allChunkHashes());
            return LocalDocsFileOutcome.skippedFile();
        }
        if (chunkingOutcome.generatedNoChunks()) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
        }
        return LocalDocsFileOutcome.failedFile(
                new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
    }

    private void prunePreviouslyIngestedFileStrict(
            QdrantCollectionKind collectionKind,
            String url,
            Optional<LocalStoreService.FileIngestionRecord> priorIngestionRecord)
            throws IOException {
        String collectionName = storage.hybridVector().resolveCollectionName(collectionKind);
        LocalStoreService.FileIngestionRecord previousFileRecord = priorIngestionRecord.orElse(null);
        ingestedFilePruneService.pruneCollectionFileStrict(collectionName, url, previousFileRecord);
    }

    private LocalDocsFileOutcome processDocuments(
            QdrantCollectionKind collectionKind,
            Path file,
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            String fileContentFingerprint,
            List<Document> documents,
            List<String> allChunkHashes,
            long fileStartMillis) {
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            storeDocumentsWithRetry(collectionKind, documents);
        } catch (EmbeddingServiceUnavailableException embeddingException) {
            log.error(
                    "Embedding service unavailable during upsert (exception type: {})",
                    embeddingException.getClass().getSimpleName());
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "embedding-unavailable", embeddingException));
        }

        long totalDuration = System.currentTimeMillis() - fileStartMillis;
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Completed processing {}/{} chunks to Qdrant in {}ms (end-to-end) ({})",
                documents.size(),
                documents.size(),
                totalDuration,
                formattedPercent);

        markDocumentsIngested(documents);
        markFileIngested(url, fileSizeBytes, lastModifiedMillis, fileContentFingerprint, allChunkHashes);

        return LocalDocsFileOutcome.processedFile();
    }

    private ChunkProcessingService.ChunkProcessingOutcome forceChunking(
            Path file, String fileName, String bodyText, String url, String title, String packageName)
            throws IOException {
        var chunkProcessor = storage.chunks();
        if (fileName.endsWith(".pdf")) {
            return chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName);
        }
        return chunkProcessor.processAndStoreChunksForce(bodyText, url, title, packageName);
    }

    private void storeDocumentsWithRetry(QdrantCollectionKind collectionKind, List<Document> documents) {
        var hybridVector = storage.hybridVector();
        long startTime = System.currentTimeMillis();
        hybridVector.upsert(collectionKind, documents);
        long duration = System.currentTimeMillis() - startTime;
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Added {} hybrid vectors to {} in {}ms ({})",
                documents.size(),
                collectionKind,
                duration,
                formattedPercent);
    }

    private void markDocumentsIngested(List<Document> documents) {
        LocalStoreService localStore = storage.localStore();
        for (Document doc : documents) {
            Object hashMetadata = doc.getMetadata().get("hash");
            if (hashMetadata == null) {
                continue;
            }
            try {
                localStore.markHashIngested(hashMetadata.toString());
            } catch (IOException markHashException) {
                throw new IllegalStateException("Failed to mark hash as ingested: " + hashMetadata, markHashException);
            }
        }
    }

    private void markFileIngested(
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            String fileContentFingerprint,
            List<String> chunkHashes) {
        if (url == null || url.isBlank()) {
            return;
        }
        LocalStoreService localStore = storage.localStore();
        try {
            localStore.markFileIngested(url, fileSizeBytes, lastModifiedMillis, fileContentFingerprint, chunkHashes);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to mark file as ingested: " + url, exception);
        }
    }

    private static void applyProvenanceMetadata(List<Document> documents, IngestionProvenance provenance) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(provenance, "provenance");
        for (Document doc : documents) {
            if (!provenance.docSet().isBlank()) {
                doc.getMetadata().put("docSet", provenance.docSet());
            }
            if (!provenance.docPath().isBlank()) {
                doc.getMetadata().put("docPath", provenance.docPath());
            }
            if (!provenance.sourceName().isBlank()) {
                doc.getMetadata().put("sourceName", provenance.sourceName());
            }
            if (!provenance.sourceKind().isBlank()) {
                doc.getMetadata().put("sourceKind", provenance.sourceKind());
            }
            if (!provenance.docVersion().isBlank()) {
                doc.getMetadata().put("docVersion", provenance.docVersion());
            }
            if (!provenance.docType().isBlank()) {
                doc.getMetadata().put("docType", provenance.docType());
            }
        }
    }

    private String mapLocalPathToUrl(final Path file) {
        final String absolutePath = file.toAbsolutePath().toString().replace('\\', '/');
        return DocsSourceRegistry.resolveLocalPath(absolutePath).orElse(FILE_URL_PREFIX + absolutePath);
    }
}
