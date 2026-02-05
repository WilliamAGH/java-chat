package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import com.williamcallahan.javachat.support.RetrievalErrorClassifier;
import com.williamcallahan.javachat.support.RetrySupport;
import java.io.IOException;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests documentation content into the vector store with chunking, caching, and local snapshots.
 */
@Service
public class DocsIngestionService {
    private static final String DEFAULT_DOCS_ROOT = "data/docs";
    private static final String FILE_URL_PREFIX = "file://";
    private static final String API_PATH_SEGMENT = "/api/";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int PACKAGE_EXTRACTION_SNIPPET_LENGTH = 100;
    private final ProgressTracker progressTracker;
    private static final Logger log = LoggerFactory.getLogger(DocsIngestionService.class);
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private final String rootUrl;
    private final VectorStore vectorStore;
    private final ChunkProcessingService chunkProcessingService;
    private final LocalStoreService localStore;
    private final FileOperationsService fileOperationsService;
    private final HtmlContentExtractor htmlExtractor;
    private final PdfContentExtractor pdfExtractor;
    private final EmbeddingCacheService embeddingCache;
    private final boolean localOnlyMode;

    /**
     * Wires ingestion dependencies and determines whether to upload or cache embeddings.
     *
     * @param rootUrl root URL for documentation crawling
     * @param vectorStore vector store for embeddings
     * @param chunkProcessingService chunk processing pipeline
     * @param localStore local snapshot and chunk storage
     * @param fileOperationsService file IO helper
     * @param htmlExtractor HTML content extractor
     * @param pdfExtractor PDF content extractor
     * @param progressTracker ingestion progress tracker
     * @param embeddingCache embedding cache for local-only mode
     * @param uploadMode ingestion upload mode
     */
    public DocsIngestionService(
            @Value("${app.docs.root-url}") String rootUrl,
            VectorStore vectorStore,
            ChunkProcessingService chunkProcessingService,
            LocalStoreService localStore,
            FileOperationsService fileOperationsService,
            HtmlContentExtractor htmlExtractor,
            PdfContentExtractor pdfExtractor,
            ProgressTracker progressTracker,
            EmbeddingCacheService embeddingCache,
            @Value("${EMBEDDINGS_UPLOAD_MODE:upload}") String uploadMode) {
        this.rootUrl = rootUrl;
        this.vectorStore = vectorStore;
        this.chunkProcessingService = chunkProcessingService;
        this.localStore = localStore;
        this.fileOperationsService = fileOperationsService;
        this.htmlExtractor = htmlExtractor;
        this.pdfExtractor = pdfExtractor;
        this.progressTracker = progressTracker;
        this.embeddingCache = embeddingCache;
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
            List<org.springframework.ai.document.Document> documents = chunkingOutcome.documents();

            if (!documents.isEmpty()) {
                INDEXING_LOG.info("[INDEXING] Processing {} documents", documents.size());
                try {
                    DocumentStorageResult storageResult = storeDocumentsWithRetry(documents);
                    if (storageResult.usedPrimaryDestination()) {
                        markDocumentsIngested(documents);
                    }
                } catch (RuntimeException storageException) {
                    String destination = localOnlyMode ? "cache" : "Qdrant";
                    INDEXING_LOG.error("[INDEXING] Failed to store documents");
                    throw new IOException("Failed to store documents to " + destination, storageException);
                }
            } else {
                INDEXING_LOG.warn("[INDEXING] No documents to add for URL");
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
        return containsVersionedSpringReference(normalized, "spring-framework")
                || containsVersionedSpringReference(normalized, "spring-ai");
    }

    private boolean containsVersionedSpringReference(String normalizedPath, String springMarker) {
        if (normalizedPath == null || normalizedPath.isBlank() || springMarker == null || springMarker.isBlank()) {
            return false;
        }
        String marker = "/" + springMarker;
        int springIndex = normalizedPath.indexOf(marker);
        if (springIndex < 0) {
            return false;
        }
        int referenceIndex = normalizedPath.indexOf("/reference/", springIndex);
        if (referenceIndex < 0) {
            return false;
        }
        int versionStart = referenceIndex + "/reference/".length();
        if (versionStart >= normalizedPath.length()) {
            return false;
        }
        int versionEnd = normalizedPath.indexOf('/', versionStart);
        if (versionEnd < 0) {
            versionEnd = normalizedPath.length();
        }
        String referenceChild = normalizedPath.substring(versionStart, versionEnd);
        if (referenceChild.isBlank()) {
            return false;
        }
        char first = referenceChild.charAt(0);
        return (first >= '0' && first <= '9') || referenceChild.contains("SNAPSHOT");
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

        if (localStore.isFileIngestedAndUnchanged(url, fileSizeBytes, lastModifiedMillis)) {
            INDEXING_LOG.debug("[INDEXING] Skipping unchanged file (already ingested)");
            return new LocalFileProcessingOutcome(false, null);
        }
        boolean requiresFullReindex = localStore
                .readFileIngestionRecord(url)
                .map(record ->
                        record.fileSizeBytes() != fileSizeBytes || record.lastModifiedMillis() != lastModifiedMillis)
                .orElse(false);
        if (requiresFullReindex) {
            LocalFileProcessingOutcome pruneOutcome = prunePreviouslyIngestedFile(url, file);
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
            applyProvenanceMetadata(documents, deriveProvenance(root, file, url));
            return processDocuments(file, url, fileSizeBytes, lastModifiedMillis, documents, fileStartMillis);
        } else if (chunkingOutcome.skippedAllChunks()) {
            INDEXING_LOG.debug("[INDEXING] Skipping file where all chunks were previously ingested");
            markFileIngested(url, fileSizeBytes, lastModifiedMillis, List.of());
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

    private LocalFileProcessingOutcome prunePreviouslyIngestedFile(String url, Path file) {
        try {
            if (!localOnlyMode) {
                String expression = buildUrlDeleteExpression(url);
                RetrySupport.executeWithRetry(
                        () -> {
                            vectorStore.delete(expression);
                            return null;
                        },
                        "Qdrant delete");
            }
            localStore.deleteParsedChunksForUrl(url);
            localStore.deleteFileIngestionRecord(url);
            return null;
        } catch (IOException ioException) {
            return new LocalFileProcessingOutcome(false, failure(file, "prune-local", ioException));
        } catch (RuntimeException vectorStoreException) {
            return new LocalFileProcessingOutcome(false, failure(file, "prune-vector-store", vectorStoreException));
        }
    }

    private String buildUrlDeleteExpression(String url) {
        Objects.requireNonNull(url, "url must not be null");
        String quoted;
        if (!url.contains("\"")) {
            quoted = "\"" + url + "\"";
        } else if (!url.contains("'")) {
            quoted = "'" + url + "'";
        } else {
            throw new IllegalArgumentException("URL contains both quote types and cannot be safely deleted: " + url);
        }
        return "url == " + quoted;
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

    private record Provenance(
            String docSet, String docPath, String sourceName, String sourceKind, String docVersion, String docType) {}

    private LocalFileProcessingOutcome processDocuments(
            Path file,
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            List<org.springframework.ai.document.Document> documents,
            long fileStartMillis) {
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            DocumentStorageResult storageResult = storeDocumentsWithRetry(documents);

            // Per-file completion summary (end-to-end, including extraction + embedding + indexing)
            long totalDuration = System.currentTimeMillis() - fileStartMillis;
            String destination =
                    localOnlyMode ? "cache" : (storageResult.usedPrimaryDestination() ? "Qdrant" : "cache (fallback)");
            INDEXING_LOG.info(
                    "[INDEXING] ✔ Completed processing {}/{} chunks to {} in {}ms (end-to-end) ({})",
                    documents.size(),
                    documents.size(),
                    destination,
                    totalDuration,
                    progressTracker.formatPercent());

            // Mark hashes as processed only after confirmed primary destination write
            // Don't mark when we fell back to cache in upload mode - allows future re-upload
            if (storageResult.usedPrimaryDestination()) {
                markDocumentsIngested(documents);
                markFileIngested(url, fileSizeBytes, lastModifiedMillis, extractChunkHashes(documents));
            }

            return new LocalFileProcessingOutcome(true, null);
        } catch (RuntimeException indexingException) {
            // Non-transient errors from storeDocumentsWithRetry propagate to abort the run
            if (!RetrievalErrorClassifier.isTransientVectorStoreError(indexingException)) {
                throw indexingException;
            }
            String operation = localOnlyMode ? "cache" : "index";
            INDEXING_LOG.error(
                    "[INDEXING] ✗ Failed to {} file (exception type: {})",
                    operation,
                    indexingException.getClass().getSimpleName());
            return new LocalFileProcessingOutcome(false, failure(file, operation, indexingException));
        }
    }

    /**
     * Marks all document hashes as ingested after successful storage.
     * Logs warnings for individual failures but does not abort.
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
                log.warn(
                        "Failed to mark hash as ingested (exception type: {})",
                        markHashException.getClass().getSimpleName());
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
            log.warn(
                    "Failed to mark file as ingested (exception type: {})",
                    exception.getClass().getSimpleName());
        }
    }

    private List<String> extractChunkHashes(List<org.springframework.ai.document.Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<String> hashes = new ArrayList<>(documents.size());
        for (org.springframework.ai.document.Document doc : documents) {
            if (doc == null) {
                continue;
            }
            Object hashValue = doc.getMetadata().get("hash");
            if (hashValue == null) {
                continue;
            }
            String hash = hashValue.toString();
            if (!hash.isBlank()) {
                hashes.add(hash);
            }
        }
        return List.copyOf(hashes);
    }

    /**
     * Stores documents to vector store with retry logic, or local cache based on mode.
     * Returns a result indicating storage success and whether primary destination was used.
     */
    private DocumentStorageResult storeDocumentsWithRetry(List<org.springframework.ai.document.Document> documents) {
        long startTime = System.currentTimeMillis();

        if (localOnlyMode) {
            embeddingCache.getOrComputeEmbeddings(documents);
            long duration = System.currentTimeMillis() - startTime;
            INDEXING_LOG.info(
                    "[INDEXING] ✓ Cached {} vectors locally in {}ms ({})",
                    documents.size(),
                    duration,
                    progressTracker.formatPercent());
            return new DocumentStorageResult(true, true);
        }

        // Upload mode with retry and fallback
        try {
            RetrySupport.executeWithRetry(
                    () -> {
                        vectorStore.add(documents);
                        return null;
                    },
                    "Qdrant upload");
            long duration = System.currentTimeMillis() - startTime;
            INDEXING_LOG.info(
                    "[INDEXING] ✓ Added {} vectors to Qdrant in {}ms ({})",
                    documents.size(),
                    duration,
                    progressTracker.formatPercent());
            return new DocumentStorageResult(true, true);
        } catch (RuntimeException qdrantError) {
            if (RetrievalErrorClassifier.isTransientVectorStoreError(qdrantError)) {
                INDEXING_LOG.warn(
                        "[INDEXING] Qdrant upload failed after retries ({}), falling back to local cache",
                        qdrantError.getClass().getSimpleName());
                embeddingCache.getOrComputeEmbeddings(documents);
                long duration = System.currentTimeMillis() - startTime;
                INDEXING_LOG.info(
                        "[INDEXING] ✓ Cached {} vectors locally (fallback) in {}ms ({})",
                        documents.size(),
                        duration,
                        progressTracker.formatPercent());
                return new DocumentStorageResult(true, false);
            }
            INDEXING_LOG.error(
                    "[INDEXING] Qdrant upload failed with non-transient error ({}), not falling back",
                    qdrantError.getClass().getSimpleName());
            throw qdrantError;
        }
    }

    /**
     * Result of document storage operation.
     *
     * @param succeeded true if documents were stored successfully (to any destination)
     * @param usedPrimaryDestination true if stored to the intended destination (Qdrant in upload mode, cache in local mode)
     */
    private record DocumentStorageResult(boolean succeeded, boolean usedPrimaryDestination) {}

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
