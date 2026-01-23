package com.williamcallahan.javachat.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
//
// Use fully qualified name to avoid clash with org.jsoup.nodes.Document
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
//
import java.time.Duration;
import java.util.*;

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
            Document doc = Jsoup.connect(url).timeout((int) Duration.ofSeconds(30).toMillis()).get();
            String title = Optional.ofNullable(doc.title()).orElse("");
            // Use improved content extraction for cleaner text
            String bodyText = url.contains("/api/") ? 
                htmlExtractor.extractJavaApiContent(doc) : 
                htmlExtractor.extractCleanContent(doc);
            String packageName = extractPackage(url, bodyText);

            // Persist raw HTML snapshot
            localStore.saveHtml(url, doc.outerHtml());

            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (href.startsWith(rootUrl) && !visited.contains(href)) {
                    queue.add(href);
                }
            }

            // Use ChunkProcessingService to handle chunking and document creation
            List<org.springframework.ai.document.Document> documents =
                chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);

            // Add documents to vector store or cache
            if (!documents.isEmpty()) {
                if (localOnlyMode) {
                    // Local-only mode: compute and cache embeddings without uploading
                    INDEXING_LOG.info("[INDEXING] Caching {} documents locally", documents.size());
                    try {
                        // Compute and cache embeddings
                        embeddingCache.getOrComputeEmbeddings(documents);
                        INDEXING_LOG.info("[INDEXING] ✓ Successfully cached {} documents locally ({})",
                            documents.size(), progressTracker.formatPercent());
                        
                        // Mark hashes as ingested (cached) after successful caching
                        for (org.springframework.ai.document.Document aiDoc : documents) {
                            String hash = aiDoc.getMetadata().get("hash").toString();
                            localStore.markHashIngested(hash);
                        }
                    } catch (RuntimeException e) {
                        INDEXING_LOG.error("[INDEXING] ✗ Failed to cache documents locally (exception type: {})",
                            e.getClass().getSimpleName());
                    }
                } else {
                    // Upload mode: send to Qdrant
                    INDEXING_LOG.info("[INDEXING] Adding {} documents to Qdrant", documents.size());
                    try {
                        vectorStore.add(documents);
                        INDEXING_LOG.info("[INDEXING] ✓ Successfully added {} documents to Qdrant ({})",
                            documents.size(), progressTracker.formatPercent());
                        
                        // Mark hashes as ingested ONLY after successful addition
                        for (org.springframework.ai.document.Document aiDoc : documents) {
                            String hash = aiDoc.getMetadata().get("hash").toString();
                            localStore.markHashIngested(hash);
                        }
                    } catch (RuntimeException e) {
                        INDEXING_LOG.error("[INDEXING] ✗ Failed to add documents to Qdrant (exception type: {})",
                            e.getClass().getSimpleName());
                        throw e;
                    }
                }
            } else {
                INDEXING_LOG.warn("[INDEXING] No documents to add for URL");
            }
        }
    }

    /**
     * Ingest HTML files from a local directory mirror (e.g., data/docs/**) into the VectorStore.
     * Scans recursively for .html/.htm files, extracts text, chunks, and upserts with citation metadata.
     * Returns the number of files processed (not chunks).
     */
    public int ingestLocalDirectory(String rootDir, int maxFiles) throws IOException {
        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        Path baseDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        if (!root.startsWith(baseDir)) {
            throw new IllegalArgumentException("Local docs directory must be under " + baseDir);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }
        final int[] processed = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> pathIterator = paths
                    .filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(pathCandidate -> {
                        Path fileNamePath = pathCandidate.getFileName();
                        if (fileNamePath == null) {
                            return false;
                        }
                        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
                    })
                    .iterator();
            while (pathIterator.hasNext() && processed[0] < maxFiles) {
                Path file = pathIterator.next();
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) {
                    continue;
                }
                long fileStartMillis = System.currentTimeMillis();
                String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
                String title;
                String bodyText = null;
                String url = mapLocalPathToUrl(file);
                String packageName;
                
                if (fileName.endsWith(".pdf")) {
                    // Process PDF file
                    try {
                        // Extract title from PDF metadata or filename
                        String metadata = pdfExtractor.getPdfMetadata(file);
                        title = extractTitleFromMetadata(metadata, file.getFileName().toString());
                        packageName = "";
                        // For recognized book PDFs, point URL to public /pdfs path
                        final Optional<String> publicPdfUrl =
                            com.williamcallahan.javachat.config.DocsSourceRegistry.mapBookLocalToPublic(
                                file.toString()
                            );
                        url = publicPdfUrl.orElse(url);
                    } catch (IOException e) {
                        log.error("Failed to extract PDF content (exception type: {})",
                            e.getClass().getSimpleName());
                        continue;
                    }
                } else {
                    // Process HTML file
                    String html = fileOperationsService.readTextFile(file);
                    org.jsoup.nodes.Document doc = Jsoup.parse(html);
                    title = Optional.ofNullable(doc.title()).orElse("");
                    bodyText = url.contains("/api/") ? 
                        htmlExtractor.extractJavaApiContent(doc) : 
                        htmlExtractor.extractCleanContent(doc);
                    packageName = extractPackage(url, bodyText);
                }

                // Use ChunkProcessingService to handle chunking and document creation
                List<org.springframework.ai.document.Document> documents;
                if (fileName.endsWith(".pdf")) {
                    try {
                        documents = chunkProcessingService.processPdfAndStoreWithPages(file, url, title, packageName);
                    } catch (IOException e) {
                        log.error("PDF chunking failed (exception type: {})", e.getClass().getSimpleName());
                        continue;
                    }
                } else {
                    documents = chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);
                }

                // Add documents to vector store or cache
                if (!documents.isEmpty()) {
                    INDEXING_LOG.info("[INDEXING] Processing file with {} chunks",
                        documents.size());
                    
                    try {
                        long startTime = System.currentTimeMillis();
                        
                        if (localOnlyMode) {
                            // Local-only mode: compute and cache embeddings
                            embeddingCache.getOrComputeEmbeddings(documents);
                            long duration = System.currentTimeMillis() - startTime;
                            INDEXING_LOG.info("[INDEXING] ✓ Cached {} vectors locally in {}ms ({})",
                                documents.size(), duration, progressTracker.formatPercent());
                        } else {
                            // Upload mode: send to Qdrant with fallback to cache
                            try {
                                vectorStore.add(documents);
                                long duration = System.currentTimeMillis() - startTime;
                                INDEXING_LOG.info("[INDEXING] ✓ Added {} vectors to Qdrant in {}ms ({})",
                                    documents.size(), duration, progressTracker.formatPercent());
                            } catch (RuntimeException qdrantError) {
                                // Qdrant failed (could be full or connection issue), fallback to local cache
                                INDEXING_LOG.warn("[INDEXING] Qdrant upload failed (exception type: {}), falling back to local cache",
                                    qdrantError.getClass().getSimpleName());
                                embeddingCache.getOrComputeEmbeddings(documents);
                                long duration = System.currentTimeMillis() - startTime;
                                INDEXING_LOG.info("[INDEXING] ✓ Cached {} vectors locally (fallback) in {}ms ({})",
                                    documents.size(), duration, progressTracker.formatPercent());
                            }
                        }
                        
                        // Per-file completion summary (end-to-end, including extraction + embedding + indexing)
                        long totalDuration = System.currentTimeMillis() - fileStartMillis;
                        String destination = localOnlyMode ? "cache" : "Qdrant";
                        INDEXING_LOG.info("[INDEXING] ✔ Completed processing {}/{} chunks to {} in {}ms (end-to-end) ({})",
                            documents.size(), documents.size(), destination, totalDuration, progressTracker.formatPercent());
                        
                        // Mark hashes as processed after successful addition/caching
                        for (org.springframework.ai.document.Document aiDoc : documents) {
                            String hash = aiDoc.getMetadata().get("hash").toString();
                            localStore.markHashIngested(hash);
                        }
                        
                        processed[0]++;
                    } catch (RuntimeException e) {
                        String operation = localOnlyMode ? "cache" : "index";
                        INDEXING_LOG.error("[INDEXING] ✗ Failed to {} file (exception type: {})",
                            operation, e.getClass().getSimpleName());
                    }
                } else {
                    INDEXING_LOG.debug("[INDEXING] Skipping empty document");
                }
            }
        }
        return processed[0];
    }

    private String mapLocalPathToUrl(final Path file) {
        final String absolutePath = file.toAbsolutePath().toString().replace('\\', '/');
        final Optional<String> resolvedUrl = com.williamcallahan.javachat.config.DocsSourceRegistry
            .mapBookLocalToPublic(absolutePath)
            .or(() -> com.williamcallahan.javachat.config.DocsSourceRegistry
                .reconstructFromEmbeddedHost(absolutePath))
            .or(() -> com.williamcallahan.javachat.config.DocsSourceRegistry
                .mapLocalPrefixToRemote(absolutePath))
            .or(() -> mapKnownMirrorUrl(absolutePath));
        return resolvedUrl.orElse(FILE_URL_PREFIX + absolutePath);
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
            String m = metadata;
            // Normalize line endings
            m = m.replace("\r\n", "\n");
            // Case-insensitive search for "Title:"
            String lower = m.toLowerCase(Locale.ROOT);
            String key = "title:";
            int startIdx = lower.indexOf(key);
            if (startIdx >= 0) {
                int start = startIdx + key.length();
                int end = m.indexOf('\n', start);
                if (end == -1) end = m.length();
                return m.substring(start, end).trim();
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
        // Heuristics: if URL contains /api/ or /package-summary.html, try to extract
        if (url.contains("/api/")) {
            int idx = url.indexOf("/api/") + 5;
            String tail = url.substring(idx);
            String[] parts = tail.split("/");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.endsWith(".html")) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(p);
            }
            String pkg = sb.toString();
            if (pkg.contains(".")) return pkg;
        }
        // Fallback: scan text for "Package java." pattern
        int p = bodyText.indexOf("Package ");
        if (p >= 0) {
            int end = Math.min(bodyText.length(), p + 100);
            String snippet = bodyText.substring(p, end);
            for (String token : snippet.split("\\s+")) {
                if (token.startsWith("java.")) return token.replaceAll("[,.;]$", "");
            }
        }
        return "";
    }
}
