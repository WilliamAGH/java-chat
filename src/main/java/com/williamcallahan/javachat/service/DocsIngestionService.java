package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.ingestion.DocumentationIngestionUseCase;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests documentation content into Qdrant with chunking and local snapshots.
 *
 * <p>Remote crawling is owned by this service. Local directory ingestion is delegated to
 * {@link LocalDocsDirectoryIngestionService} to keep responsibilities narrow and files small.</p>
 */
@Service
public class DocsIngestionService implements DocumentationIngestionUseCase {
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final String rootUrl;
    private final HybridVectorService hybridVectorService;
    private final ChunkProcessingService chunkProcessingService;
    private final ContentHasher contentHasher;
    private final LocalStoreService localStore;
    private final HtmlContentExtractor htmlExtractor;
    private final LocalDocsDirectoryIngestionService localDirectoryIngestionService;

    /**
     * Wires ingestion dependencies.
     *
     * @param rootUrl root URL for documentation crawling
     * @param hybridVectorService gRPC-based hybrid vector upsert service
     * @param chunkProcessingService chunk processing pipeline
     * @param contentHasher derives deterministic point UUIDs from chunk hashes
     * @param localStore local snapshot and chunk storage
     * @param htmlExtractor HTML content extractor
     * @param localDirectoryIngestionService local directory ingestion delegate
     */
    @Autowired
    public DocsIngestionService(
            @Value("${app.docs.root-url}") String rootUrl,
            HybridVectorService hybridVectorService,
            ChunkProcessingService chunkProcessingService,
            ContentHasher contentHasher,
            LocalStoreService localStore,
            HtmlContentExtractor htmlExtractor,
            LocalDocsDirectoryIngestionService localDirectoryIngestionService) {
        this.rootUrl = Objects.requireNonNull(rootUrl, "rootUrl");
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.chunkProcessingService = Objects.requireNonNull(chunkProcessingService, "chunkProcessingService");
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher");
        this.localStore = Objects.requireNonNull(localStore, "localStore");
        this.htmlExtractor = Objects.requireNonNull(htmlExtractor, "htmlExtractor");
        this.localDirectoryIngestionService =
                Objects.requireNonNull(localDirectoryIngestionService, "localDirectoryIngestionService");
    }

    /**
     * Crawls from the configured root URL and ingests up to the requested page limit.
     */
    @Override
    public void crawlAndIngest(int maxPages) throws IOException {
        crawlAndIngest(maxPages, DocsIngestionService::fetchPageSnapshot);
    }

    void crawlAndIngest(int maxPages, CrawlPageFetcher crawlPageFetcher) throws IOException {
        CrawlPageFetcher requiredPageFetcher = Objects.requireNonNull(crawlPageFetcher, "crawlPageFetcher");
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootUrl);
        while (!queue.isEmpty() && visited.size() < maxPages) {
            String url = queue.poll();
            if (!visited.add(url)) continue;
            if (!url.startsWith(rootUrl)) continue;

            CrawlPageSnapshot pageSnapshot = requiredPageFetcher.fetch(url);
            Document sourceDocument = pageSnapshot.document();
            String title = Optional.ofNullable(sourceDocument.title()).orElse("");

            localStore.saveHtml(url, pageSnapshot.rawHtml());

            for (String href : pageSnapshot.discoveredLinks()) {
                if (href.startsWith(rootUrl) && !visited.contains(href)) {
                    queue.add(href);
                }
            }

            if (JavaPackageExtractor.isJavaApiUrl(url)) {
                JavaApiPageExtraction javaApiExtraction = htmlExtractor.extractJavaApiPage(sourceDocument);
                if (javaApiExtraction.excluded()) {
                    hybridVectorService.deleteByUrl(QdrantCollectionKind.DOCS, url);
                    INDEXING_LOG.debug("[INDEXING] Skipping class-use Java API page content");
                    continue;
                }
                String packageName = JavaPackageExtractor.extractPackage(url, javaApiExtraction.combinedText());
                ChunkProcessingService.JavaApiPage javaApiPage = javaApiPageFor(
                                url, title, packageName, javaApiExtraction)
                        .orElseThrow();
                ChunkProcessingService.ChunkProcessingOutcome initialChunkingOutcome =
                        chunkProcessingService.processAndStoreJavaApiPage(javaApiPage);
                if (initialChunkingOutcome.generatedNoChunks()) {
                    INDEXING_LOG.debug("[INDEXING] No chunks generated for URL");
                    continue;
                }
                if (initialChunkingOutcome.skippedAllChunks()
                        && hybridVectorService.hasExactPointIdsForUrl(
                                QdrantCollectionKind.DOCS,
                                url,
                                expectedPointUuids(initialChunkingOutcome.allChunkHashes()))) {
                    INDEXING_LOG.debug("[INDEXING] Skipping unchanged Java API page with complete vector coverage");
                    continue;
                }

                List<org.springframework.ai.document.Document> replacementDocuments;
                if (hasCompleteReplacement(initialChunkingOutcome)) {
                    replacementDocuments = requireCompleteReplacement(initialChunkingOutcome);
                } else {
                    ChunkProcessingService.ChunkProcessingOutcome replacementChunkingOutcome =
                            chunkProcessingService.processAndStoreJavaApiPageForce(javaApiPage);
                    replacementDocuments = requireCompleteReplacement(replacementChunkingOutcome);
                }
                applyJavaApiDocumentType(replacementDocuments);
                replaceAndMarkDocuments(url, replacementDocuments);
                continue;
            }

            String extractedText = htmlExtractor.extractCleanContent(sourceDocument);
            String packageName = JavaPackageExtractor.extractPackage(url, extractedText);
            ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome =
                    chunkProcessingService.processAndStoreChunks(extractedText, url, title, packageName);
            if (chunkingOutcome.generatedNoChunks()) {
                INDEXING_LOG.debug("[INDEXING] No chunks generated for URL");
                continue;
            }
            if (chunkingOutcome.skippedAllChunks()
                    && hybridVectorService.hasExactPointIdsForUrl(
                            QdrantCollectionKind.DOCS, url, expectedPointUuids(chunkingOutcome.allChunkHashes()))) {
                INDEXING_LOG.debug("[INDEXING] Skipping URL with exact vector identity coverage");
                continue;
            }
            if (chunkingOutcome.skippedAllChunks()) {
                INDEXING_LOG.warn(
                        "[INDEXING] Hash markers exist but Qdrant point identities differ; forcing replacement");
            }
            List<org.springframework.ai.document.Document> replacementDocuments;
            if (hasCompleteReplacement(chunkingOutcome)) {
                replacementDocuments = requireCompleteReplacement(chunkingOutcome);
            } else {
                ChunkProcessingService.ChunkProcessingOutcome replacementChunkingOutcome =
                        chunkProcessingService.processAndStoreChunksForce(extractedText, url, title, packageName);
                replacementDocuments = requireCompleteReplacement(replacementChunkingOutcome);
            }
            replaceAndMarkDocuments(url, replacementDocuments);
        }
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     */
    @Override
    public IngestionLocalOutcome ingestLocalDirectory(String rootDirectory, int maxFiles) throws IOException {
        return localDirectoryIngestionService.ingestLocalDirectory(rootDirectory, maxFiles);
    }

    private void replaceAndMarkDocuments(
            String sourceUrl, List<org.springframework.ai.document.Document> replacementDocuments) throws IOException {
        try {
            hybridVectorService.replaceUrlDocuments(QdrantCollectionKind.DOCS, sourceUrl, replacementDocuments);
            markDocumentsIngested(replacementDocuments);
        } catch (RuntimeException storageException) {
            throw new IOException("Failed to replace documents in Qdrant", storageException);
        }
    }

    private void markDocumentsIngested(List<org.springframework.ai.document.Document> documents) {
        for (org.springframework.ai.document.Document indexedDocument : documents) {
            Object hashMetadata = indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.HASH_FIELD);
            if (hashMetadata == null) {
                continue;
            }
            String title = DocumentFactory.metadataText(indexedDocument, QdrantPayloadFieldSchema.TITLE_FIELD);
            String packageName = DocumentFactory.metadataText(indexedDocument, QdrantPayloadFieldSchema.PACKAGE_FIELD);
            try {
                localStore.markHashIngested(hashMetadata.toString(), title, packageName);
            } catch (IOException markHashException) {
                throw new IllegalStateException("Failed to mark hash as ingested: " + hashMetadata, markHashException);
            }
        }
    }

    private static void applyJavaApiDocumentType(List<org.springframework.ai.document.Document> documents) {
        for (org.springframework.ai.document.Document indexedDocument : documents) {
            indexedDocument
                    .getMetadata()
                    .put(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
        }
    }

    private static List<org.springframework.ai.document.Document> requireCompleteReplacement(
            ChunkProcessingService.ChunkProcessingOutcome replacementChunkingOutcome) {
        if (!hasCompleteReplacement(replacementChunkingOutcome)) {
            throw new IllegalStateException("Replacement must contain one document for every chunk");
        }
        return replacementChunkingOutcome.documents();
    }

    private static boolean hasCompleteReplacement(ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome) {
        List<org.springframework.ai.document.Document> chunkDocuments = chunkingOutcome.documents();
        return !chunkDocuments.isEmpty()
                && chunkingOutcome.skippedChunks() == 0
                && chunkDocuments.size() == chunkingOutcome.totalChunks();
    }

    private List<String> expectedPointUuids(List<String> chunkHashes) {
        return chunkHashes.stream().map(contentHasher::uuidFromHash).toList();
    }

    static CrawlPageSnapshot prepareCrawlPageSnapshot(String url, String rawHtml) {
        Document document = Jsoup.parse(rawHtml, url);
        Set<String> discoveredLinks = new LinkedHashSet<>();
        for (Element anchorElement : document.select("a[href]")) {
            String absoluteHref = anchorElement.attr("abs:href");
            String fragmentlessHref = removeUrlFragment(absoluteHref);
            if (!fragmentlessHref.isBlank()) {
                discoveredLinks.add(fragmentlessHref);
            }
        }
        return new CrawlPageSnapshot(document, rawHtml, List.copyOf(discoveredLinks));
    }

    private static String removeUrlFragment(String absoluteHref) {
        int fragmentSeparatorIndex = absoluteHref.indexOf('#');
        return fragmentSeparatorIndex < 0 ? absoluteHref : absoluteHref.substring(0, fragmentSeparatorIndex);
    }

    private static CrawlPageSnapshot fetchPageSnapshot(String url) throws IOException {
        org.jsoup.Connection.Response httpResponse = Jsoup.connect(url)
                .timeout((int) HTTP_CONNECT_TIMEOUT.toMillis())
                .maxBodySize(0)
                .execute();
        String rawHtml = Optional.ofNullable(httpResponse.body()).orElse("");
        return prepareCrawlPageSnapshot(url, rawHtml);
    }

    /**
     * Maps one extracted Java API page to the chunking contract while retaining exact member anchors.
     *
     * <p>Class-use pages remain crawlable and snapshotable, but supply no indexable page contract.
     * This keeps their consumer listings out of retrieval without discarding their discovered links.</p>
     *
     * @param sourceUrl fragmentless Java API page URL
     * @param title source page title
     * @param packageName Java package inferred from the source URL and extracted text
     * @param javaApiExtraction structured extraction from the source page
     * @return indexable Java API page, or empty only for a class-use page
     */
    static Optional<ChunkProcessingService.JavaApiPage> javaApiPageFor(
            String sourceUrl, String title, String packageName, JavaApiPageExtraction javaApiExtraction) {
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(packageName, "packageName");
        JavaApiPageExtraction requiredExtraction = Objects.requireNonNull(javaApiExtraction, "javaApiExtraction");
        if (requiredExtraction.excluded()) {
            return Optional.empty();
        }

        List<ChunkProcessingService.JavaApiPageSegment> pageSegments = new java.util.ArrayList<>();
        if (!requiredExtraction.overviewText().isBlank()) {
            pageSegments.add(ChunkProcessingService.JavaApiPageSegment.overview(requiredExtraction.overviewText()));
        }
        for (JavaApiAnchoredSection anchoredSection : requiredExtraction.anchoredSections()) {
            pageSegments.add(
                    ChunkProcessingService.JavaApiPageSegment.member(anchoredSection.text(), anchoredSection.anchor()));
        }
        return Optional.of(new ChunkProcessingService.JavaApiPage(sourceUrl, title, packageName, pageSegments));
    }

    record CrawlPageSnapshot(Document document, String rawHtml, List<String> discoveredLinks) {}

    /**
     * Fetches one source page into an immutable crawl snapshot.
     *
     * <p>The test seam keeps the crawler's HTTP boundary outside ingestion policy, allowing the
     * latter to prove deletion and forced re-indexing without opening a live network connection.</p>
     */
    @FunctionalInterface
    interface CrawlPageFetcher {

        /**
         * Fetches one source URL and preserves its raw response snapshot.
         *
         * @param sourceUrl source URL to fetch
         * @return parsed and raw source snapshot
         * @throws IOException when the source cannot be fetched
         */
        CrawlPageSnapshot fetch(String sourceUrl) throws IOException;
    }
}
