package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests documentation content into the vector store with chunking, caching, and local snapshots.
 */
@Service
public class DocsIngestionService {
    private static final String DEFAULT_DOCS_ROOT = "data/docs";
    private static final String QUARANTINE_DIR_NAME = ".quarantine";
    private static final String FILE_URL_PREFIX = "file://";
    private static final String API_PATH_SEGMENT = "/api/";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int PACKAGE_EXTRACTION_SNIPPET_LENGTH = 100;
    private static final int MIN_GUARD_TEXT_LENGTH = 1000;
    private static final int MIN_GUARD_WORD_COUNT = 150;
    private static final double MIN_GUARD_ALPHA_RATIO = 0.4;
    private static final String GUARD_LOADING_TOKEN = "loading";
    private static final String GUARD_PAGE_TOKEN = "page";
    private static final String GUARD_ENABLE_JS_TOKEN = "enable javascript";
    private static final java.time.format.DateTimeFormatter QUARANTINE_TIMESTAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private final ProgressTracker progressTracker;
    private static final Logger log = LoggerFactory.getLogger(DocsIngestionService.class);
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private final String rootUrl;
    private final HybridVectorService hybridVectorService;
    private final ChunkProcessingService chunkProcessingService;
    private final ContentHasher hasher;
    private final LocalStoreService localStore;
    private final FileOperationsService fileOperationsService;
    private final HtmlContentExtractor htmlExtractor;
    private final PdfContentExtractor pdfExtractor;
    private final EmbeddingCacheService embeddingCache;
    private final QdrantCollectionRouter collectionRouter;
    private final boolean localOnlyMode;

    /**
     * Wires ingestion dependencies and determines whether to upload or cache embeddings.
     *
     * @param rootUrl root URL for documentation crawling
     * @param hybridVectorService gRPC-based hybrid vector upsert service
     * @param chunkProcessingService chunk processing pipeline
     * @param hasher content hash helper for deterministic vector IDs
     * @param localStore local snapshot and chunk storage
     * @param fileOperationsService file IO helper
     * @param htmlExtractor HTML content extractor
     * @param pdfExtractor PDF content extractor
     * @param progressTracker ingestion progress tracker
     * @param embeddingCache embedding cache for local-only mode
     * @param collectionRouter routes documents to the correct Qdrant collection
     * @param uploadMode ingestion upload mode
     */
    public DocsIngestionService(
            @Value("${app.docs.root-url}") String rootUrl,
            HybridVectorService hybridVectorService,
            ChunkProcessingService chunkProcessingService,
            ContentHasher hasher,
            LocalStoreService localStore,
            FileOperationsService fileOperationsService,
            HtmlContentExtractor htmlExtractor,
            PdfContentExtractor pdfExtractor,
            ProgressTracker progressTracker,
            EmbeddingCacheService embeddingCache,
            QdrantCollectionRouter collectionRouter,
            @Value("${EMBEDDINGS_UPLOAD_MODE:upload}") String uploadMode) {
        this.rootUrl = rootUrl;
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.chunkProcessingService = chunkProcessingService;
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.localStore = localStore;
        this.fileOperationsService = fileOperationsService;
        this.htmlExtractor = htmlExtractor;
        this.pdfExtractor = pdfExtractor;
        this.progressTracker = progressTracker;
        this.embeddingCache = embeddingCache;
        this.collectionRouter = Objects.requireNonNull(collectionRouter, "collectionRouter");
        this.localOnlyMode = "local-only".equals(uploadMode);

        if (localOnlyMode) {
            INDEXING_LOG.info("[INDEXING] Running in LOCAL-ONLY mode - embeddings will be cached locally");
        } else {
            INDEXING_LOG.info("[INDEXING] Running in UPLOAD mode - embeddings will be sent to Qdrant");
        }
    }

    /**
     * Crawls from the configured root URL and ingests up to the requested page limit.
     */
    public void crawlAndIngest(int maxPages) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootUrl);
        while (!queue.isEmpty() && visited.size() < maxPages) {
            String url = queue.poll();
            if (!visited.add(url)) continue;
            if (!url.startsWith(rootUrl)) continue;
            org.jsoup.Connection.Response response = Jsoup.connect(url)
                    .timeout((int) HTTP_CONNECT_TIMEOUT.toMillis())
                    .maxBodySize(0)
                    .execute();
            String rawHtml = Optional.ofNullable(response.body()).orElse("");
            CrawlPageSnapshot pageSnapshot = prepareCrawlPageSnapshot(url, rawHtml);
            Document doc = pageSnapshot.document();
            String title = Optional.ofNullable(doc.title()).orElse("");
            Document extractionDoc = doc.clone();
            String bodyText = url.contains(API_PATH_SEGMENT)
                    ? htmlExtractor.extractJavaApiContent(extractionDoc)
                    : htmlExtractor.extractCleanContent(extractionDoc);
            String packageName = extractPackage(url, bodyText);
            localStore.saveHtml(url, pageSnapshot.rawHtml());

            for (String href : pageSnapshot.discoveredLinks()) {
                if (href.startsWith(rootUrl) && !visited.contains(href)) {
                    queue.add(href);
                }
            }

            ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome =
                    chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);
            if (chunkingOutcome.generatedNoChunks()) {
                INDEXING_LOG.debug("[INDEXING] No chunks generated for URL");
                continue;
            }
            if (chunkingOutcome.skippedAllChunks()) {
                INDEXING_LOG.debug("[INDEXING] Skipping URL (all chunks already ingested)");
                continue;
            }

            List<org.springframework.ai.document.Document> documents = chunkingOutcome.documents();
            if (!documents.isEmpty()) {
                INDEXING_LOG.info("[INDEXING] Processing {} documents", documents.size());
                try {
                    storeDocumentsWithRetry(QdrantCollectionKind.DOCS, documents);
                    markDocumentsIngested(documents);
                } catch (RuntimeException storageException) {
                    String destination = localOnlyMode ? "cache" : "Qdrant";
                    INDEXING_LOG.error("[INDEXING] Failed to store documents");
                    throw new IOException("Failed to store documents to " + destination, storageException);
                }
            } else {
                INDEXING_LOG.debug("[INDEXING] No documents to add for URL");
            }
        }
    }

    /**
     * Ingest HTML files from a local directory mirror (e.g., data/docs/**) into the VectorStore.
     * Scans recursively for .html/.htm files, extracts text, chunks, and upserts with citation metadata.
     * Returns processing outcomes including per-file failures.
     */
    public LocalIngestionOutcome ingestLocalDirectory(String rootDir, int maxFiles) throws IOException {
        if (rootDir == null || rootDir.isBlank()) {
            throw new IllegalArgumentException("Local docs directory is required");
        }
        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        Path baseDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        Path rootRoot = root.getRoot();
        Path absoluteBaseDir =
                rootRoot == null ? baseDir : rootRoot.resolve(DEFAULT_DOCS_ROOT).normalize();
        if (!root.startsWith(baseDir) && !root.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException("Local docs directory must be under " + absoluteBaseDir);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }
        AtomicInteger processedCount = new AtomicInteger(0);
        List<LocalIngestionFailure> failures = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> pathIterator = paths.filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(this::isIngestableFile)
                    .iterator();
            while (pathIterator.hasNext() && processedCount.get() < maxFiles) {
                Path file = pathIterator.next();
                if (file.getFileName() == null) continue;

                LocalFileProcessingOutcome outcome = processLocalFile(root, file);
                if (outcome.processed()) {
                    processedCount.incrementAndGet();
                } else if (outcome.failure() != null) {
                    failures.add(outcome.failure());
                }
            }
        }
        return new LocalIngestionOutcome(processedCount.get(), failures);
    }

    private boolean isIngestableFile(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        if (shouldSkipVersionedSpringReference(path)) {
            return false;
        }
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
    }

    private boolean shouldSkipVersionedSpringReference(Path path) {
        if (path == null) {
            return false;
        }
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return shouldSkipSpringFrameworkReference(normalized) || shouldSkipSpringAiReference(normalized);
    }

    private boolean shouldSkipSpringFrameworkReference(String normalizedPath) {
        return containsDisallowedVersionedSpringReference(normalizedPath, "spring-framework", version -> true);
    }

    private boolean shouldSkipSpringAiReference(String normalizedPath) {
        return containsDisallowedVersionedSpringReference(
                normalizedPath, "spring-ai", version -> !version.startsWith("2."));
    }

    private boolean containsDisallowedVersionedSpringReference(
            String normalizedPath, String springMarker, java.util.function.Predicate<String> versionDisallowed) {
        return extractReferenceSubdirectory(normalizedPath, springMarker)
                .filter(this::isVersionedOrSnapshot)
                .filter(subdir -> isSnapshot(subdir) || versionDisallowed.test(subdir))
                .isPresent();
    }

    private Optional<String> extractReferenceSubdirectory(String normalizedPath, String springMarker) {
        if (normalizedPath == null || normalizedPath.isBlank() || springMarker == null || springMarker.isBlank()) {
            return Optional.empty();
        }
        String marker = "/" + springMarker;
        int springIndex = normalizedPath.indexOf(marker);
        if (springIndex < 0) {
            return Optional.empty();
        }
        int referenceIndex = normalizedPath.indexOf("/reference/", springIndex);
        if (referenceIndex < 0) {
            return Optional.empty();
        }
        int versionStart = referenceIndex + "/reference/".length();
        if (versionStart >= normalizedPath.length()) {
            return Optional.empty();
        }
        int versionEnd = normalizedPath.indexOf('/', versionStart);
        if (versionEnd < 0) {
            versionEnd = normalizedPath.length();
        }
        String subdirectory = normalizedPath.substring(versionStart, versionEnd);
        return subdirectory.isBlank() ? Optional.empty() : Optional.of(subdirectory);
    }

    private boolean isVersionedOrSnapshot(String subdirectory) {
        return isSnapshot(subdirectory) || startsWithDigit(subdirectory);
    }

    private boolean isSnapshot(String subdirectory) {
        return subdirectory.contains("SNAPSHOT");
    }

    private boolean startsWithDigit(String subdirectory) {
        return !subdirectory.isEmpty() && Character.isDigit(subdirectory.charAt(0));
    }

    private LocalFileProcessingOutcome processLocalFile(Path root, Path file) {
        long fileStartMillis = System.currentTimeMillis();
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return new LocalFileProcessingOutcome(
                    false, new LocalIngestionFailure(file.toString(), "filename", "Missing filename"));
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        String url = mapLocalPathToUrl(file);
        if (fileName.endsWith(".pdf")) {
            // For recognized book PDFs, point URL to public /pdfs path
            final Optional<String> publicPdfUrl = DocsSourceRegistry.mapBookLocalToPublic(file.toString());
            url = publicPdfUrl.orElse(url);
        }

        final long fileSizeBytes;
        final long lastModifiedMillis;
        try {
            fileSizeBytes = Files.size(file);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException attributeException) {
            return new LocalFileProcessingOutcome(false, failure(file, "file-attributes", attributeException));
        }

        Provenance provenance = deriveProvenance(root, file, url);
        QdrantCollectionKind collectionKind =
                collectionRouter.route(provenance.docSet(), provenance.docPath(), provenance.docType(), url);

        Optional<LocalStoreService.FileIngestionRecord> priorIngestionRecord = localStore.readFileIngestionRecord(url);
        if (priorIngestionRecord
                .map(record ->
                        record.fileSizeBytes() == fileSizeBytes && record.lastModifiedMillis() == lastModifiedMillis)
                .orElse(false)) {
            INDEXING_LOG.debug("[INDEXING] Skipping unchanged file (already ingested)");
            return new LocalFileProcessingOutcome(false, null);
        }
        boolean requiresFullReindex = priorIngestionRecord
                .map(record ->
                        record.fileSizeBytes() != fileSizeBytes || record.lastModifiedMillis() != lastModifiedMillis)
                .orElse(false);
        if (requiresFullReindex) {
            LocalFileProcessingOutcome pruneOutcome =
                    prunePreviouslyIngestedFile(collectionKind, url, file, priorIngestionRecord);
            if (pruneOutcome != null) {
                return pruneOutcome;
            }
        }
        String title;
        String bodyText = null;
        String packageName;

        if (fileName.endsWith(".pdf")) {
            try {
                String metadata = pdfExtractor.getPdfMetadata(file);
                title = extractTitleFromMetadata(metadata, fileNamePath.toString());
                packageName = "";
            } catch (IOException pdfExtractionException) {
                log.error(
                        "Failed to extract PDF content (exception type: {})",
                        pdfExtractionException.getClass().getSimpleName());
                return new LocalFileProcessingOutcome(false, failure(file, "pdf-extraction", pdfExtractionException));
            }
        } else {
            try {
                String html = fileOperationsService.readTextFile(file);
                org.jsoup.nodes.Document doc = Jsoup.parse(html);
                title = Optional.ofNullable(doc.title()).orElse("");
                bodyText = url.contains(API_PATH_SEGMENT)
                        ? htmlExtractor.extractJavaApiContent(doc)
                        : htmlExtractor.extractCleanContent(doc);
                packageName = extractPackage(url, bodyText);
            } catch (IOException htmlReadException) {
                log.error(
                        "Failed to read HTML file (exception type: {})",
                        htmlReadException.getClass().getSimpleName());
                return new LocalFileProcessingOutcome(false, failure(file, "html-read", htmlReadException));
            }

            ContentGuardOutcome guardOutcome = evaluateContentGuard(bodyText);
            if (!guardOutcome.isAcceptable()) {
                LocalFileProcessingOutcome quarantineOutcome =
                        quarantineInvalidContent(file, guardOutcome.rejectionReason());
                return quarantineOutcome == null
                        ? new LocalFileProcessingOutcome(
                                false,
                                new LocalIngestionFailure(
                                        file.toString(), "content-guard", guardOutcome.rejectionReason()))
                        : quarantineOutcome;
            }
        }

        ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome;
        try {
            if (fileName.endsWith(".pdf")) {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessingService.processPdfAndStoreWithPagesForce(file, url, title, packageName)
                        : chunkProcessingService.processPdfAndStoreWithPages(file, url, title, packageName);
            } else {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessingService.processAndStoreChunksForce(bodyText, url, title, packageName)
                        : chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);
            }
        } catch (IOException chunkingException) {
            log.error(
                    "Chunking failed (exception type: {})",
                    chunkingException.getClass().getSimpleName());
            return new LocalFileProcessingOutcome(false, failure(file, "chunking", chunkingException));
        }

        List<org.springframework.ai.document.Document> documents = chunkingOutcome.documents();
        if (!documents.isEmpty()) {
            applyProvenanceMetadata(documents, provenance);
            return processDocuments(
                    collectionKind,
                    file,
                    url,
                    fileSizeBytes,
                    lastModifiedMillis,
                    documents,
                    chunkingOutcome.allChunkHashes(),
                    fileStartMillis);
        } else if (chunkingOutcome.skippedAllChunks()) {
            INDEXING_LOG.debug("[INDEXING] Skipping file where all chunks were previously ingested");
            markFileIngested(url, fileSizeBytes, lastModifiedMillis, chunkingOutcome.allChunkHashes());
            return new LocalFileProcessingOutcome(false, null);
        } else if (chunkingOutcome.generatedNoChunks()) {
            INDEXING_LOG.debug("[INDEXING] Skipping file that produced no chunks");
            return new LocalFileProcessingOutcome(
                    false, new LocalIngestionFailure(file.toString(), "empty-document", "No content to chunk"));
        } else {
            INDEXING_LOG.debug("[INDEXING] Skipping empty document");
            return new LocalFileProcessingOutcome(
                    false, new LocalIngestionFailure(file.toString(), "empty-document", "No chunks generated"));
        }
    }

    private LocalFileProcessingOutcome prunePreviouslyIngestedFile(
            QdrantCollectionKind collectionKind,
            String url,
            Path file,
            Optional<LocalStoreService.FileIngestionRecord> priorIngestionRecord) {
        try {
            if (!localOnlyMode) {
                hybridVectorService.deleteByUrl(collectionKind, url);
            }
            List<String> priorChunkHashes = resolveChunkHashesForPrune(url, priorIngestionRecord);
            if (!priorChunkHashes.isEmpty()) {
                localStore.deleteChunkIngestionMarkers(priorChunkHashes);
                embeddingCache.evictByChunkHashes(priorChunkHashes);
            }
            localStore.deleteParsedChunksForUrl(url);
            localStore.deleteFileIngestionRecord(url);
            return null;
        } catch (IOException ioException) {
            return new LocalFileProcessingOutcome(false, failure(file, "prune-local", ioException));
        } catch (RuntimeException runtimeException) {
            return new LocalFileProcessingOutcome(false, failure(file, "prune-runtime", runtimeException));
        }
    }

    private List<String> resolveChunkHashesForPrune(
            String url, Optional<LocalStoreService.FileIngestionRecord> priorIngestionRecord) throws IOException {
        if (priorIngestionRecord != null && priorIngestionRecord.isPresent()) {
            List<String> hashes = priorIngestionRecord.get().chunkHashes();
            if (hashes != null && !hashes.isEmpty()) {
                return hashes;
            }
        }
        return reconstructChunkHashesFromParsedChunks(url);
    }

    private List<String> reconstructChunkHashesFromParsedChunks(String url) throws IOException {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        Path parsedDir = localStore.getParsedDir();
        if (parsedDir == null || !Files.isDirectory(parsedDir)) {
            return List.of();
        }

        String safeName = localStore.toSafeName(url);
        String prefix = safeName + "_";
        Set<String> hashes = new LinkedHashSet<>();

        try (var stream = Files.newDirectoryStream(parsedDir, path -> {
            Path fileNamePath = path.getFileName();
            if (fileNamePath == null) {
                return false;
            }
            String fileName = fileNamePath.toString();
            return fileName.startsWith(prefix) && fileName.endsWith(".txt");
        })) {
            for (Path chunkPath : stream) {
                Path fileNamePath = chunkPath.getFileName();
                if (fileNamePath == null) {
                    continue;
                }
                String fileName = fileNamePath.toString();
                String remainder = fileName.substring(prefix.length());
                int underscore = remainder.indexOf('_');
                if (underscore <= 0) {
                    continue;
                }
                String indexToken = remainder.substring(0, underscore);
                int chunkIndex;
                try {
                    chunkIndex = Integer.parseInt(indexToken);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                String chunkText = Files.readString(chunkPath, StandardCharsets.UTF_8);
                String hash = hasher.generateChunkHash(url, chunkIndex, chunkText == null ? "" : chunkText);
                if (!hash.isBlank()) {
                    hashes.add(hash);
                }
            }
        }
        return List.copyOf(hashes);
    }

    private Provenance deriveProvenance(Path root, Path file, String url) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(file, "file");

        Path baseDocsDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        String docSet = "";
        if (root.startsWith(baseDocsDir)) {
            docSet = baseDocsDir.relativize(root).toString();
        }

        String docPath = "";
        if (file.startsWith(root)) {
            docPath = root.relativize(file).toString();
        }

        String sourceName = deriveSourceName(docSet, url);
        String sourceKind = deriveSourceKind(sourceName);
        String docType = deriveDocType(docSet, url);
        String docVersion = deriveDocVersion(docSet, url);

        return new Provenance(
                blankToNull(docSet),
                blankToNull(docPath),
                blankToNull(sourceName),
                blankToNull(sourceKind),
                blankToNull(docVersion),
                blankToNull(docType));
    }

    private static void applyProvenanceMetadata(
            List<org.springframework.ai.document.Document> documents, Provenance provenance) {
        if (documents == null || documents.isEmpty() || provenance == null) {
            return;
        }
        for (org.springframework.ai.document.Document doc : documents) {
            if (doc == null) {
                continue;
            }
            if (provenance.docSet() != null) {
                doc.getMetadata().put("docSet", provenance.docSet());
            }
            if (provenance.docPath() != null) {
                doc.getMetadata().put("docPath", provenance.docPath());
            }
            if (provenance.sourceName() != null) {
                doc.getMetadata().put("sourceName", provenance.sourceName());
            }
            if (provenance.sourceKind() != null) {
                doc.getMetadata().put("sourceKind", provenance.sourceKind());
            }
            if (provenance.docVersion() != null) {
                doc.getMetadata().put("docVersion", provenance.docVersion());
            }
            if (provenance.docType() != null) {
                doc.getMetadata().put("docType", provenance.docType());
            }
        }
    }

    private static String deriveSourceName(String docSet, String url) {
        if (docSet != null) {
            String normalized = docSet.replace('\\', '/');
            if (normalized.startsWith("oracle/") || normalized.startsWith("java/")) {
                return "oracle";
            }
            if (normalized.startsWith("ibm/")) {
                return "ibm";
            }
            if (normalized.startsWith("jetbrains/")) {
                return "jetbrains";
            }
            int slash = normalized.indexOf('/');
            if (slash > 0) {
                return normalized.substring(0, slash);
            }
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        if (url != null) {
            String lower = AsciiTextNormalizer.toLowerAscii(url);
            if (lower.contains("docs.oracle.com") || lower.contains("oracle.com")) {
                return "oracle";
            }
            if (lower.contains("developer.ibm.com") || lower.contains("ibm.com")) {
                return "ibm";
            }
            if (lower.contains("jetbrains.com")) {
                return "jetbrains";
            }
        }
        return "";
    }

    private static String deriveSourceKind(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "";
        }
        String lower = AsciiTextNormalizer.toLowerAscii(sourceName);
        if ("oracle".equals(lower)) {
            return "official";
        }
        if ("ibm".equals(lower) || "jetbrains".equals(lower)) {
            return "vendor";
        }
        return "unknown";
    }

    private static String deriveDocType(String docSet, String url) {
        String normalized = docSet == null ? "" : docSet.replace('\\', '/');
        if (normalized.startsWith("books")) {
            return "book";
        }
        if (normalized.startsWith("java/")) {
            return "api-docs";
        }
        if (normalized.startsWith("oracle/javase")) {
            return "release-notes";
        }
        if (normalized.startsWith("ibm/articles") || normalized.startsWith("jetbrains/")) {
            return "blog";
        }
        if (url != null) {
            String lower = AsciiTextNormalizer.toLowerAscii(url);
            if (lower.contains("docs.oracle.com/en/java/javase/")) {
                return "api-docs";
            }
            if (lower.contains("/pdfs/")) {
                return "pdf";
            }
        }
        return "";
    }

    private static String deriveDocVersion(String docSet, String url) {
        String normalized = docSet == null ? "" : docSet.replace('\\', '/');
        String fromDocSet = firstNumberToken(normalized);
        if (!fromDocSet.isBlank()) {
            return fromDocSet;
        }
        if (url != null) {
            String fromUrl = firstNumberToken(AsciiTextNormalizer.toLowerAscii(url));
            return fromUrl;
        }
        return "";
    }

    private static String firstNumberToken(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        return digits.toString();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private ContentGuardOutcome evaluateContentGuard(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return ContentGuardOutcome.rejected("empty body text");
        }

        int textLength = bodyText.length();
        if (textLength < MIN_GUARD_TEXT_LENGTH) {
            return ContentGuardOutcome.rejected("body text too short (length=" + textLength + ")");
        }

        WordAlphaStats stats = scanWordAndAlphaStats(bodyText);
        if (stats.wordCount() < MIN_GUARD_WORD_COUNT) {
            return ContentGuardOutcome.rejected("word count too low (words=" + stats.wordCount() + ")");
        }
        if (stats.alphaRatio() < MIN_GUARD_ALPHA_RATIO) {
            return ContentGuardOutcome.rejected(
                    String.format(Locale.ROOT, "alpha ratio too low (ratio=%.2f)", stats.alphaRatio()));
        }

        String normalized = AsciiTextNormalizer.toLowerAscii(bodyText);
        boolean hasLoadingPage = normalized.contains(GUARD_LOADING_TOKEN) && normalized.contains(GUARD_PAGE_TOKEN);
        boolean hasEnableJavascript = normalized.contains(GUARD_ENABLE_JS_TOKEN);
        if (hasLoadingPage || hasEnableJavascript) {
            return ContentGuardOutcome.rejected("page appears to require JavaScript (loading/enable javascript)");
        }

        return ContentGuardOutcome.accepted();
    }

    private WordAlphaStats scanWordAndAlphaStats(String bodyText) {
        int wordCount = 0;
        int alphaCount = 0;
        int nonWhitespaceCount = 0;
        boolean inWord = false;

        for (int index = 0; index < bodyText.length(); index++) {
            char ch = bodyText.charAt(index);
            if (!Character.isWhitespace(ch)) {
                nonWhitespaceCount++;
            }
            if (Character.isLetter(ch)) {
                alphaCount++;
            }
            boolean wordChar = Character.isLetterOrDigit(ch);
            if (wordChar && !inWord) {
                wordCount++;
                inWord = true;
            } else if (!wordChar) {
                inWord = false;
            }
        }

        double alphaRatio = nonWhitespaceCount == 0 ? 0.0 : (double) alphaCount / nonWhitespaceCount;
        return new WordAlphaStats(wordCount, alphaRatio);
    }

    private LocalFileProcessingOutcome quarantineInvalidContent(Path file, String rejectionReason) {
        try {
            Path quarantineTarget = buildQuarantineTarget(file);
            Files.createDirectories(quarantineTarget.getParent());
            Files.move(file, quarantineTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            INDEXING_LOG.warn(
                    "[INDEXING] ⚠ Content guard rejected file; quarantined to {} (reason={})",
                    quarantineTarget,
                    rejectionReason);
            return new LocalFileProcessingOutcome(
                    false,
                    new LocalIngestionFailure(file.toString(), "content-guard", "quarantined: " + rejectionReason));
        } catch (IOException quarantineException) {
            log.warn(
                    "Failed to quarantine invalid content (exception type: {})",
                    quarantineException.getClass().getSimpleName());
            return new LocalFileProcessingOutcome(false, failure(file, "content-guard", quarantineException));
        }
    }

    private Path buildQuarantineTarget(Path file) {
        Path baseDocsDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path relativePath = absoluteFile.startsWith(baseDocsDir)
                ? baseDocsDir.relativize(absoluteFile)
                : Path.of(absoluteFile.getFileName().toString());

        String timestamp = java.time.LocalDateTime.now().format(QUARANTINE_TIMESTAMP_FORMAT);
        String originalName = relativePath.getFileName().toString();
        String quarantinedName = appendTimestamp(originalName, timestamp);

        Path quarantineRoot = baseDocsDir.resolve(QUARANTINE_DIR_NAME);
        Path targetParent = quarantineRoot.resolve(relativePath).getParent();
        return targetParent.resolve(quarantinedName);
    }

    private String appendTimestamp(String fileName, String timestamp) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + "." + timestamp + fileName.substring(dotIndex);
        }
        return fileName + "." + timestamp;
    }

    private record WordAlphaStats(int wordCount, double alphaRatio) {}

    private record ContentGuardOutcome(boolean isAcceptable, String rejectionReason) {
        static ContentGuardOutcome accepted() {
            return new ContentGuardOutcome(true, "");
        }

        static ContentGuardOutcome rejected(String reason) {
            return new ContentGuardOutcome(false, reason == null ? "invalid content" : reason);
        }
    }

    private record Provenance(
            String docSet, String docPath, String sourceName, String sourceKind, String docVersion, String docType) {}

    private LocalFileProcessingOutcome processDocuments(
            QdrantCollectionKind collectionKind,
            Path file,
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            List<org.springframework.ai.document.Document> documents,
            List<String> allChunkHashes,
            long fileStartMillis) {
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            storeDocumentsWithRetry(collectionKind, documents);

            // Per-file completion summary (end-to-end, including extraction + embedding + indexing)
            long totalDuration = System.currentTimeMillis() - fileStartMillis;
            String destination = localOnlyMode ? "cache" : "Qdrant";
            INDEXING_LOG.info(
                    "[INDEXING] ✔ Completed processing {}/{} chunks to {} in {}ms (end-to-end) ({})",
                    documents.size(),
                    documents.size(),
                    destination,
                    totalDuration,
                    progressTracker.formatPercent());

            // Mark hashes as processed only after confirmed destination write.
            markDocumentsIngested(documents);
            markFileIngested(url, fileSizeBytes, lastModifiedMillis, allChunkHashes);

            return new LocalFileProcessingOutcome(true, null);
        } catch (RuntimeException indexingException) {
            // Strict propagation: do not continue ingesting after destination write failures.
            throw indexingException;
        }
    }

    /**
     * Marks all document hashes as ingested after successful storage.
     * Fails fast on persistence errors to avoid inconsistent incremental state.
     */
    private void markDocumentsIngested(List<org.springframework.ai.document.Document> documents) {
        for (org.springframework.ai.document.Document doc : documents) {
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

    private void markFileIngested(String url, long fileSizeBytes, long lastModifiedMillis, List<String> chunkHashes) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            localStore.markFileIngested(url, fileSizeBytes, lastModifiedMillis, chunkHashes);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to mark file as ingested: " + url, exception);
        }
    }

    /**
     * Stores documents to vector store with retry logic, or local cache based on mode.
     * Returns a result indicating storage success and whether primary destination was used.
     */
    private void storeDocumentsWithRetry(List<org.springframework.ai.document.Document> documents) {
        storeDocumentsWithRetry(QdrantCollectionKind.DOCS, documents);
    }

    /**
     * Stores documents to the correct Qdrant collection (upload mode) or to the local cache (local-only mode).
     *
     * <p>In upload mode, a single gRPC upsert writes both dense and sparse named vectors
     * atomically for each document.</p>
     */
    private void storeDocumentsWithRetry(
            QdrantCollectionKind collectionKind, List<org.springframework.ai.document.Document> documents) {
        long startTime = System.currentTimeMillis();

        if (localOnlyMode) {
            embeddingCache.getOrComputeEmbeddings(documents);
            long duration = System.currentTimeMillis() - startTime;
            INDEXING_LOG.info(
                    "[INDEXING] ✓ Cached {} vectors locally in {}ms ({})",
                    documents.size(),
                    duration,
                    progressTracker.formatPercent());
            return;
        }

        try {
            hybridVectorService.upsert(collectionKind, documents);

            long duration = System.currentTimeMillis() - startTime;
            INDEXING_LOG.info(
                    "[INDEXING] ✓ Added {} hybrid vectors to Qdrant in {}ms ({})",
                    documents.size(),
                    duration,
                    progressTracker.formatPercent());
        } catch (RuntimeException qdrantError) {
            INDEXING_LOG.error(
                    "[INDEXING] Qdrant hybrid upsert failed (exception type: {})",
                    qdrantError.getClass().getSimpleName());
            throw qdrantError;
        }
    }

    /**
     * Constructs a failure record with exception-specific diagnostic context.
     *
     * @param file the file that failed processing
     * @param phase the processing phase where failure occurred
     * @param exception the exception that caused the failure
     * @return failure record with detailed diagnostics
     */
    private LocalIngestionFailure failure(Path file, String phase, Exception exception) {
        StringBuilder details = new StringBuilder();
        details.append(exception.getClass().getSimpleName());

        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            details.append(": ").append(message);
        }

        String diagnosticHint = ExceptionDiagnostics.getDiagnosticHint(exception);
        if (diagnosticHint != null) {
            details.append(" [").append(diagnosticHint).append("]");
        } else if (exception.getCause() != null) {
            details.append(" [caused by: ")
                    .append(exception.getCause().getClass().getSimpleName())
                    .append("]");
        }

        return new LocalIngestionFailure(file.toString(), phase, details.toString());
    }

    /**
     * Maps exception types to user-friendly diagnostic hints.
     * Extensible via the static registration method without modifying failure().
     */
    private static final class ExceptionDiagnostics {
        private static final Map<Class<? extends Exception>, String> HINTS = new ConcurrentHashMap<>();

        static {
            register(java.io.FileNotFoundException.class, "file not found or inaccessible");
            register(java.nio.file.AccessDeniedException.class, "permission denied");
            register(java.nio.charset.MalformedInputException.class, "file encoding issue - not valid UTF-8");
            register(java.nio.file.NoSuchFileException.class, "file does not exist");
        }

        static void register(Class<? extends Exception> exceptionType, String hint) {
            HINTS.put(exceptionType, hint);
        }

        static String getDiagnosticHint(Exception exception) {
            if (exception == null) {
                return null;
            }
            return HINTS.get(exception.getClass());
        }
    }

    private String mapLocalPathToUrl(final Path file) {
        final String absolutePath = file.toAbsolutePath().toString().replace('\\', '/');
        return DocsSourceRegistry.resolveLocalPath(absolutePath).orElse(FILE_URL_PREFIX + absolutePath);
    }

    private String extractTitleFromMetadata(String metadata, String fileName) {
        // Simple heuristic: try to find a Title in metadata (case-insensitive), otherwise use filename
        if (metadata != null && !metadata.isBlank()) {
            // Normalize line endings
            String normalizedMetadata = metadata.replace("\r\n", "\n");
            // Case-insensitive search for "Title:"
            String lowerMetadata = AsciiTextNormalizer.toLowerAscii(normalizedMetadata);
            String titleKey = "title:";
            int titleKeyIndex = lowerMetadata.indexOf(titleKey);
            if (titleKeyIndex >= 0) {
                int valueStart = titleKeyIndex + titleKey.length();
                int valueEnd = normalizedMetadata.indexOf('\n', valueStart);
                if (valueEnd == -1) valueEnd = normalizedMetadata.length();
                return normalizedMetadata.substring(valueStart, valueEnd).trim();
            }
        }
        // Fallback to using the filename, removing extension
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    private String extractPackage(String url, String bodyText) {
        if (url.contains(API_PATH_SEGMENT)) {
            int idx = url.indexOf(API_PATH_SEGMENT) + API_PATH_SEGMENT.length();
            String tail = url.substring(idx);
            String[] parts = tail.split("/");
            StringBuilder sb = new StringBuilder();
            for (String pathSegment : parts) {
                if (pathSegment.endsWith(".html")) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(pathSegment);
            }
            String pkg = sb.toString();
            if (pkg.contains(".")) return pkg;
        }
        int packageIndex = bodyText.indexOf("Package ");
        if (packageIndex >= 0) {
            int end = Math.min(bodyText.length(), packageIndex + PACKAGE_EXTRACTION_SNIPPET_LENGTH);
            String snippet = bodyText.substring(packageIndex, end);
            for (String token : snippet.split("\\s+")) {
                if (token.startsWith("java.")) return token.replaceAll("[,.;]$", "");
            }
        }
        return "";
    }

    static CrawlPageSnapshot prepareCrawlPageSnapshot(String url, String rawHtml) {
        Document document = Jsoup.parse(rawHtml, url);
        List<String> discoveredLinks = new ArrayList<>();
        for (Element anchorElement : document.select("a[href]")) {
            String href = anchorElement.attr("abs:href");
            if (!href.isBlank()) {
                discoveredLinks.add(href);
            }
        }
        return new CrawlPageSnapshot(document, rawHtml, List.copyOf(discoveredLinks));
    }

    record CrawlPageSnapshot(Document document, String rawHtml, List<String> discoveredLinks) {}

    /**
     * Represents a local ingestion failure with file and phase context.
     *
     * @param filePath absolute file path
     * @param phase ingestion phase that failed
     * @param details failure details for diagnostics
     */
    public record LocalIngestionFailure(String filePath, String phase, String details) {
        public LocalIngestionFailure {
            if (filePath == null || filePath.isBlank()) {
                throw new IllegalArgumentException("File path is required");
            }
            if (phase == null || phase.isBlank()) {
                throw new IllegalArgumentException("Failure phase is required");
            }
            details = details == null ? "" : details;
        }
    }

    /**
     * Captures processed count and per-file failures for local ingestion runs.
     *
     * @param processedCount number of successfully processed files
     * @param failures per-file failures encountered during ingestion
     */
    public record LocalIngestionOutcome(int processedCount, List<LocalIngestionFailure> failures) {
        public LocalIngestionOutcome {
            if (processedCount < 0) {
                throw new IllegalArgumentException("Processed count must be non-negative");
            }
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /**
     * Internal result of processing a single local file.
     *
     * @param processed true if file was successfully processed and stored
     * @param failure failure details when processing failed; null when skipped or processed successfully
     */
    private record LocalFileProcessingOutcome(boolean processed, LocalIngestionFailure failure) {
        LocalFileProcessingOutcome {
            if (processed && failure != null) {
                throw new IllegalStateException("Success outcome must not include failure details");
            }
        }
    }
}
