package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import com.williamcallahan.javachat.support.RetrievalErrorClassifier;
import com.williamcallahan.javachat.support.RetrySupport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ingests documentation content into the vector store with chunking, caching, and local snapshots.
 */
@Service
public class DocsIngestionService {
    private static final String DEFAULT_DOCS_ROOT = "data/docs";
    private static final String SPRING_BOOT_REFERENCE_PATH = "/data/docs/spring-boot/reference.html";
    private static final String SPRING_BOOT_REFERENCE_URL =
        "https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/";
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
    public DocsIngestionService(@Value("${app.docs.root-url}") String rootUrl,
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
            String bodyText = url.contains(API_PATH_SEGMENT) ?
                htmlExtractor.extractJavaApiContent(extractionDoc) :
                htmlExtractor.extractCleanContent(extractionDoc);
            String packageName = extractPackage(url, bodyText);
            localStore.saveHtml(url, pageSnapshot.rawHtml());

            for (String href : pageSnapshot.discoveredLinks()) {
                if (href.startsWith(rootUrl) && !visited.contains(href)) {
                    queue.add(href);
                }
            }

            List<org.springframework.ai.document.Document> documents =
                chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);

            if (!documents.isEmpty()) {
                INDEXING_LOG.info("[INDEXING] Processing {} documents", documents.size());
                try {
                    DocumentStorageResult storageResult = storeDocumentsWithRetry(documents);
                    if (storageResult.usedPrimaryDestination()) {
                        markDocumentsIngested(documents);
                    }
                } catch (RuntimeException storageException) {
                    String destination = localOnlyMode ? "cache" : "Qdrant";
                    INDEXING_LOG.error("[INDEXING] ✗ Failed to store documents to {}", destination, storageException);
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
        Path absoluteBaseDir = rootRoot == null
            ? baseDir
            : rootRoot.resolve(DEFAULT_DOCS_ROOT).normalize();
        if (!root.startsWith(baseDir) && !root.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException("Local docs directory must be under " + absoluteBaseDir);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }
        AtomicInteger processedCount = new AtomicInteger(0);
        List<LocalIngestionFailure> failures = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> pathIterator = paths
                    .filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(this::isIngestableFile)
                    .iterator();
            while (pathIterator.hasNext() && processedCount.get() < maxFiles) {
                Path file = pathIterator.next();
                if (file.getFileName() == null) continue;

                LocalFileProcessingOutcome outcome = processLocalFile(file);
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
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
    }

    private LocalFileProcessingOutcome processLocalFile(Path file) {
        long fileStartMillis = System.currentTimeMillis();
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return new LocalFileProcessingOutcome(false,
                new LocalIngestionFailure(file.toString(), "filename", "Missing filename"));
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        String title;
        String bodyText = null;
        String url = mapLocalPathToUrl(file);
        String packageName;
        
        if (fileName.endsWith(".pdf")) {
            try {
                String metadata = pdfExtractor.getPdfMetadata(file);
                title = extractTitleFromMetadata(metadata, fileNamePath.toString());
                packageName = "";
                // For recognized book PDFs, point URL to public /pdfs path
                final Optional<String> publicPdfUrl = DocsSourceRegistry.mapBookLocalToPublic(file.toString());
                url = publicPdfUrl.orElse(url);
            } catch (IOException pdfExtractionException) {
                log.error("Failed to extract PDF content (exception type: {})",
                    pdfExtractionException.getClass().getSimpleName());
                return new LocalFileProcessingOutcome(false,
                    failure(file, "pdf-extraction", pdfExtractionException));
            }
        } else {
            try {
                String html = fileOperationsService.readTextFile(file);
                org.jsoup.nodes.Document doc = Jsoup.parse(html);
                title = Optional.ofNullable(doc.title()).orElse("");
                bodyText = url.contains(API_PATH_SEGMENT) ?
                    htmlExtractor.extractJavaApiContent(doc) :
                    htmlExtractor.extractCleanContent(doc);
                packageName = extractPackage(url, bodyText);
            } catch (IOException htmlReadException) {
                log.error("Failed to read HTML file (exception type: {})", htmlReadException.getClass().getSimpleName());
                return new LocalFileProcessingOutcome(false,
                    failure(file, "html-read", htmlReadException));
            }
        }

        List<org.springframework.ai.document.Document> documents;
        try {
            if (fileName.endsWith(".pdf")) {
                documents = chunkProcessingService.processPdfAndStoreWithPages(file, url, title, packageName);
            } else {
                documents = chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);
            }
        } catch (IOException chunkingException) {
            log.error("Chunking failed (exception type: {})", chunkingException.getClass().getSimpleName());
            return new LocalFileProcessingOutcome(false,
                failure(file, "chunking", chunkingException));
        }

        if (!documents.isEmpty()) {
            return processDocuments(file, documents, fileStartMillis);
        } else {
            INDEXING_LOG.debug("[INDEXING] Skipping empty document");
            return new LocalFileProcessingOutcome(false,
                new LocalIngestionFailure(file.toString(), "empty-document", "No chunks generated"));
        }
    }

    private LocalFileProcessingOutcome processDocuments(Path file,
                                                       List<org.springframework.ai.document.Document> documents,
                                                       long fileStartMillis) {
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            DocumentStorageResult storageResult = storeDocumentsWithRetry(documents);

            // Per-file completion summary (end-to-end, including extraction + embedding + indexing)
            long totalDuration = System.currentTimeMillis() - fileStartMillis;
            String destination = localOnlyMode ? "cache" :
                (storageResult.usedPrimaryDestination() ? "Qdrant" : "cache (fallback)");
            INDEXING_LOG.info("[INDEXING] ✔ Completed processing {}/{} chunks to {} in {}ms (end-to-end) ({})",
                documents.size(), documents.size(), destination, totalDuration, progressTracker.formatPercent());

            // Mark hashes as processed only after confirmed primary destination write
            // Don't mark when we fell back to cache in upload mode - allows future re-upload
            if (storageResult.usedPrimaryDestination()) {
                markDocumentsIngested(documents);
            }

            return new LocalFileProcessingOutcome(true, null);
        } catch (RuntimeException indexingException) {
            // Non-transient errors from storeDocumentsWithRetry propagate to abort the run
            if (!RetrievalErrorClassifier.isTransientVectorStoreError(indexingException)) {
                throw indexingException;
            }
            String operation = localOnlyMode ? "cache" : "index";
            INDEXING_LOG.error("[INDEXING] ✗ Failed to {} file (exception type: {})",
                operation, indexingException.getClass().getSimpleName());
            return new LocalFileProcessingOutcome(false,
                failure(file, operation, indexingException));
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
                log.warn("Failed to mark hash as ingested (exception type: {})",
                    markHashException.getClass().getSimpleName());
            }
        }
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
            INDEXING_LOG.info("[INDEXING] ✓ Cached {} vectors locally in {}ms ({})",
                documents.size(), duration, progressTracker.formatPercent());
            return new DocumentStorageResult(true, true);
        }

        // Upload mode with retry and fallback
        try {
            RetrySupport.executeWithRetry(
                () -> { vectorStore.add(documents); return null; },
                "Qdrant upload"
            );
            long duration = System.currentTimeMillis() - startTime;
            INDEXING_LOG.info("[INDEXING] ✓ Added {} vectors to Qdrant in {}ms ({})",
                documents.size(), duration, progressTracker.formatPercent());
            return new DocumentStorageResult(true, true);
        } catch (RuntimeException qdrantError) {
            if (RetrievalErrorClassifier.isTransientVectorStoreError(qdrantError)) {
                INDEXING_LOG.warn("[INDEXING] Qdrant upload failed after retries ({}), falling back to local cache",
                    qdrantError.getClass().getSimpleName());
                embeddingCache.getOrComputeEmbeddings(documents);
                long duration = System.currentTimeMillis() - startTime;
                INDEXING_LOG.info("[INDEXING] ✓ Cached {} vectors locally (fallback) in {}ms ({})",
                    documents.size(), duration, progressTracker.formatPercent());
                return new DocumentStorageResult(true, false);
            }
            INDEXING_LOG.error("[INDEXING] Qdrant upload failed with non-transient error ({}), not falling back",
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
        return DocsSourceRegistry.resolveLocalPath(absolutePath)
            .or(() -> mapKnownMirrorUrl(absolutePath))
            .orElse(FILE_URL_PREFIX + absolutePath);
    }

    private Optional<String> mapKnownMirrorUrl(final String absolutePath) {
        if (absolutePath.contains(SPRING_BOOT_REFERENCE_PATH)) {
            return Optional.of(SPRING_BOOT_REFERENCE_URL);
        }
        return Optional.empty();
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
     * @param processed true if file was successfully processed
     * @param failure failure details when processing failed, must be non-null if processed is false
     */
    private record LocalFileProcessingOutcome(boolean processed, LocalIngestionFailure failure) {
        LocalFileProcessingOutcome {
            if (!processed && failure == null) {
                throw new IllegalStateException("Failure details required when file processing fails");
            }
            if (processed && failure != null) {
                throw new IllegalStateException("Success outcome must not include failure details");
            }
        }
    }
}
