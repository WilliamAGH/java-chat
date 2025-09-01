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
import java.nio.file.Paths;
import java.util.stream.Stream;
//
import java.time.Duration;
import java.util.*;

@Service
public class DocsIngestionService {
    private static final Logger log = LoggerFactory.getLogger(DocsIngestionService.class);
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");
    
    private final String rootUrl;
    private final VectorStore vectorStore;
    private final ChunkProcessingService chunkProcessingService;
    private final LocalStoreService localStore;
    private final FileOperationsService fileOperationsService;
    private final HtmlContentExtractor htmlExtractor;

    public DocsIngestionService(@Value("${app.docs.root-url}") String rootUrl,
                                VectorStore vectorStore,
                                ChunkProcessingService chunkProcessingService,
                                LocalStoreService localStore,
                                FileOperationsService fileOperationsService,
                                HtmlContentExtractor htmlExtractor) {
        this.rootUrl = rootUrl;
        this.vectorStore = vectorStore;
        this.chunkProcessingService = chunkProcessingService;
        this.localStore = localStore;
        this.fileOperationsService = fileOperationsService;
        this.htmlExtractor = htmlExtractor;
    }

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

            // Add documents to vector store
            if (!documents.isEmpty()) {
                INDEXING_LOG.info("[INDEXING] Adding {} documents to vector store for URL: {}", 
                    documents.size(), url);
                try {
                    vectorStore.add(documents);
                    INDEXING_LOG.info("[INDEXING] ✓ Successfully added {} documents to Qdrant", 
                        documents.size());
                    
                    // Mark hashes as ingested ONLY after successful addition
                    for (org.springframework.ai.document.Document aiDoc : documents) {
                        String hash = aiDoc.getMetadata().get("hash").toString();
                        localStore.markHashIngested(hash);
                    }
                } catch (Exception e) {
                    INDEXING_LOG.error("[INDEXING] ✗ Failed to add documents to Qdrant: {}", 
                        e.getMessage());
                    throw e;
                }
            } else {
                INDEXING_LOG.warn("[INDEXING] No documents to add for URL: {}", url);
            }
        }
    }

    /**
     * Ingest HTML files from a local directory mirror (e.g., data/docs/**) into the VectorStore.
     * Scans recursively for .html/.htm files, extracts text, chunks, and upserts with citation metadata.
     * Returns the number of files processed (not chunks).
     */
    public int ingestLocalDirectory(String rootDir, int maxFiles) throws IOException {
        Path root = Paths.get(rootDir);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }
        final int[] processed = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> it = paths
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".html") || name.endsWith(".htm");
                    })
                    .iterator();
            while (it.hasNext() && processed[0] < maxFiles) {
                Path file = it.next();
                String html = fileOperationsService.readTextFile(file);
                org.jsoup.nodes.Document doc = Jsoup.parse(html);
                String title = Optional.ofNullable(doc.title()).orElse("");
                // Use improved content extraction for cleaner text
                String url = mapLocalPathToUrl(file);
                String bodyText = url.contains("/api/") ? 
                    htmlExtractor.extractJavaApiContent(doc) : 
                    htmlExtractor.extractCleanContent(doc);

                String packageName = extractPackage(url, bodyText);

                // Use ChunkProcessingService to handle chunking and document creation
                List<org.springframework.ai.document.Document> documents =
                    chunkProcessingService.processAndStoreChunks(bodyText, url, title, packageName);

                // Add documents to vector store
                if (!documents.isEmpty()) {
                    INDEXING_LOG.info("[INDEXING] Processing file: {} with {} chunks", 
                        file.getFileName(), documents.size());
                    INDEXING_LOG.debug("[INDEXING] First chunk preview: {}", 
                        documents.get(0).getText().substring(0, Math.min(100, documents.get(0).getText().length())));
                    
                    try {
                        long startTime = System.currentTimeMillis();
                        vectorStore.add(documents);
                        long duration = System.currentTimeMillis() - startTime;
                        
                        INDEXING_LOG.info("[INDEXING] ✓ Added {} vectors to Qdrant in {}ms for file: {}", 
                            documents.size(), duration, file.getFileName());
                        
                        // Mark hashes as ingested ONLY after successful addition
                        for (org.springframework.ai.document.Document aiDoc : documents) {
                            String hash = aiDoc.getMetadata().get("hash").toString();
                            localStore.markHashIngested(hash);
                        }
                        
                        processed[0]++;
                    } catch (Exception e) {
                        INDEXING_LOG.error("[INDEXING] ✗ Failed to index {}: {}", 
                            file.getFileName(), e.getMessage());
                        log.error("Indexing error details:", e);
                    }
                } else {
                    INDEXING_LOG.debug("[INDEXING] Skipping empty document: {}", file.getFileName());
                }
            }
        }
        return processed[0];
    }

    private String mapLocalPathToUrl(Path file) {
        String p = file.toAbsolutePath().toString().replace('\\', '/');
        int idxSpring = p.indexOf("docs.spring.io/");
        if (idxSpring >= 0) {
            String suffix = p.substring(idxSpring);
            return "https://" + suffix;
        }
        int idxJava = p.indexOf("download.java.net/");
        if (idxJava >= 0) {
            String suffix = p.substring(idxJava);
            return "https://" + suffix;
        }
        // Known single-file mirrors
        if (p.contains("/data/docs/spring-boot/reference.html")) {
            return "https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/";
        }
        // Fallback to file:// URL for traceability
        return "file://" + p;
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


