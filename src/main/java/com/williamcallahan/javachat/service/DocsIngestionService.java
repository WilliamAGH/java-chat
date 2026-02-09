package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import com.williamcallahan.javachat.service.ingestion.JavaPackageExtractor;
import com.williamcallahan.javachat.service.ingestion.LocalDocsDirectoryIngestionService;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests documentation content into Qdrant with chunking and local snapshots.
 *
 * <p>Remote crawling is owned by this service. Local directory ingestion is delegated to
 * {@link LocalDocsDirectoryIngestionService} to keep responsibilities narrow and files small.</p>
 */
@Service
public class DocsIngestionService {
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private static final String API_PATH_SEGMENT = "/api/";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final String rootUrl;
    private final HybridVectorService hybridVectorService;
    private final ChunkProcessingService chunkProcessingService;
    private final LocalStoreService localStore;
    private final HtmlContentExtractor htmlExtractor;
    private final ProgressTracker progressTracker;
    private final LocalDocsDirectoryIngestionService localDirectoryIngestionService;

    /**
     * Wires ingestion dependencies.
     *
     * @param rootUrl root URL for documentation crawling
     * @param hybridVectorService gRPC-based hybrid vector upsert service
     * @param chunkProcessingService chunk processing pipeline
     * @param localStore local snapshot and chunk storage
     * @param htmlExtractor HTML content extractor
     * @param progressTracker ingestion progress tracker
     * @param localDirectoryIngestionService local directory ingestion delegate
     */
    public DocsIngestionService(
            @Value("${app.docs.root-url}") String rootUrl,
            HybridVectorService hybridVectorService,
            ChunkProcessingService chunkProcessingService,
            LocalStoreService localStore,
            HtmlContentExtractor htmlExtractor,
            ProgressTracker progressTracker,
            LocalDocsDirectoryIngestionService localDirectoryIngestionService) {
        this.rootUrl = Objects.requireNonNull(rootUrl, "rootUrl");
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.chunkProcessingService = Objects.requireNonNull(chunkProcessingService, "chunkProcessingService");
        this.localStore = Objects.requireNonNull(localStore, "localStore");
        this.htmlExtractor = Objects.requireNonNull(htmlExtractor, "htmlExtractor");
        this.progressTracker = Objects.requireNonNull(progressTracker, "progressTracker");
        this.localDirectoryIngestionService =
                Objects.requireNonNull(localDirectoryIngestionService, "localDirectoryIngestionService");
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
            String packageName = JavaPackageExtractor.extractPackage(url, bodyText);

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
            if (documents.isEmpty()) {
                INDEXING_LOG.debug("[INDEXING] No documents to add for URL");
                continue;
            }

            try {
                storeDocumentsWithRetry(QdrantCollectionKind.DOCS, documents);
                markDocumentsIngested(documents);
            } catch (RuntimeException storageException) {
                throw new IOException("Failed to store documents to Qdrant", storageException);
            }
        }
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     */
    public IngestionLocalOutcome ingestLocalDirectory(String rootDir, int maxFiles) throws IOException {
        return localDirectoryIngestionService.ingestLocalDirectory(rootDir, maxFiles);
    }

    private void storeDocumentsWithRetry(
            QdrantCollectionKind collectionKind, List<org.springframework.ai.document.Document> documents) {
        long startTime = System.currentTimeMillis();
        hybridVectorService.upsert(collectionKind, documents);

        long duration = System.currentTimeMillis() - startTime;
        INDEXING_LOG.info(
                "[INDEXING] Added {} hybrid vectors to Qdrant in {}ms ({})",
                documents.size(),
                duration,
                progressTracker.formatPercent());
    }

    private void markDocumentsIngested(List<org.springframework.ai.document.Document> documents) {
        for (org.springframework.ai.document.Document doc : documents) {
            Object hashMetadata = doc.getMetadata().get("hash");
            if (hashMetadata == null) {
                continue;
            }
            String title = metadataText(doc, "title");
            String packageName = metadataText(doc, "package");
            try {
                localStore.markHashIngested(hashMetadata.toString(), title, packageName);
            } catch (IOException markHashException) {
                throw new IllegalStateException("Failed to mark hash as ingested: " + hashMetadata, markHashException);
            }
        }
    }

    private String metadataText(org.springframework.ai.document.Document document, String metadataKey) {
        Object metadataRaw = document.getMetadata().get(metadataKey);
        if (metadataRaw == null) {
            return "";
        }
        return metadataRaw.toString();
    }

    static CrawlPageSnapshot prepareCrawlPageSnapshot(String url, String rawHtml) {
        Document document = Jsoup.parse(rawHtml, url);
        java.util.List<String> discoveredLinks = new java.util.ArrayList<>();
        for (Element anchorElement : document.select("a[href]")) {
            String href = anchorElement.attr("abs:href");
            if (!href.isBlank()) {
                discoveredLinks.add(href);
            }
        }
        return new CrawlPageSnapshot(document, rawHtml, List.copyOf(discoveredLinks));
    }

    record CrawlPageSnapshot(Document document, String rawHtml, List<String> discoveredLinks) {}
}
