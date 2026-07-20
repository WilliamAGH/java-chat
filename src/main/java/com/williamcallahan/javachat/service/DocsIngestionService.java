package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.ingestion.DocumentationIngestionUseCase;
import com.williamcallahan.javachat.application.ingestion.FileLimit;
import com.williamcallahan.javachat.application.ingestion.PageLimit;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import com.williamcallahan.javachat.service.ingestion.JavaPackageExtractor;
import com.williamcallahan.javachat.service.ingestion.LocalDocsDirectoryIngestionService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final int HTTP_REDIRECT_STATUS_MINIMUM = 300;
    private static final int HTTP_REDIRECT_STATUS_MAXIMUM = 399;
    private static final int MAXIMUM_CRAWL_REDIRECTS = 10;
    private static final String HTTP_CONTENT_TYPE_HEADER = "Content-Type";
    private static final String HTTP_LOCATION_HEADER = "Location";
    static final int MAX_RESPONSE_BODY_BYTES = 5_242_880;
    private static final int RESPONSE_BODY_SIZE_PROBE_BYTES = MAX_RESPONSE_BODY_BYTES + 1;

    private final CrawlBoundary crawlBoundary;
    private final HybridVectorService hybridVectorService;
    private final ChunkProcessingService chunkProcessingService;
    private final ContentHasher contentHasher;
    private final LocalStoreService localStore;
    private final HtmlContentExtractor htmlExtractor;
    private final LocalDocsDirectoryIngestionService localDirectoryIngestionService;

    /**
     * Wires ingestion dependencies.
     *
     * @param appProperties canonical application configuration containing the documentation root URL
     * @param hybridVectorService gRPC-based hybrid vector upsert service
     * @param chunkProcessingService chunk processing pipeline
     * @param contentHasher derives deterministic point UUIDs from chunk hashes
     * @param localStore local snapshot and chunk storage
     * @param htmlExtractor HTML content extractor
     * @param localDirectoryIngestionService local directory ingestion delegate
     */
    public DocsIngestionService(
            AppProperties appProperties,
            HybridVectorService hybridVectorService,
            ChunkProcessingService chunkProcessingService,
            ContentHasher contentHasher,
            LocalStoreService localStore,
            HtmlContentExtractor htmlExtractor,
            LocalDocsDirectoryIngestionService localDirectoryIngestionService) {
        AppProperties requiredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.crawlBoundary = CrawlBoundary.from(requiredAppProperties.getDocs().getRootUrl());
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
    public void crawlAndIngest(PageLimit pageLimit) throws IOException {
        crawlAndIngest(pageLimit, this::fetchPageSnapshot);
    }

    void crawlAndIngest(PageLimit pageLimit, CrawlPageFetcher crawlPageFetcher) throws IOException {
        PageLimit requiredPageLimit = Objects.requireNonNull(pageLimit, "pageLimit");
        CrawlPageFetcher requiredPageFetcher = Objects.requireNonNull(crawlPageFetcher, "crawlPageFetcher");
        Set<String> visitedSourceUrls = new LinkedHashSet<>();
        Deque<String> pendingSourceUrls = new ArrayDeque<>();
        pendingSourceUrls.add(crawlBoundary.rootUrl());
        while (!pendingSourceUrls.isEmpty() && visitedSourceUrls.size() < requiredPageLimit.maximumPages()) {
            String sourceUrl = pendingSourceUrls.poll();
            if (!crawlBoundary.contains(sourceUrl) || !visitedSourceUrls.add(sourceUrl)) {
                continue;
            }

            CrawlPageSnapshot pageSnapshot = requiredPageFetcher.fetch(sourceUrl);
            crawlBoundary.requireContainsRedirectTarget(pageSnapshot.finalUrl());
            Document sourceDocument = pageSnapshot.document();
            String title = Optional.ofNullable(sourceDocument.title()).orElse("");

            localStore.saveHtml(sourceUrl, pageSnapshot.rawHtml());

            for (String candidateSourceUrl : pageSnapshot.discoveredLinks()) {
                if (crawlBoundary.contains(candidateSourceUrl) && !visitedSourceUrls.contains(candidateSourceUrl)) {
                    pendingSourceUrls.add(candidateSourceUrl);
                }
            }

            if (JavaPackageExtractor.isJavaApiUrl(sourceUrl)) {
                JavaApiPageExtraction javaApiExtraction = htmlExtractor.extractJavaApiPage(sourceDocument);
                if (javaApiExtraction.excluded()) {
                    hybridVectorService.deleteByUrl(QdrantCollectionKind.DOCS, sourceUrl);
                    INDEXING_LOG.debug("[INDEXING] Skipping class-use Java API page content");
                    continue;
                }
                String packageName = JavaPackageExtractor.extractPackage(sourceUrl, javaApiExtraction.combinedText());
                ChunkProcessingService.JavaApiPage javaApiPage = javaApiPageFor(
                                sourceUrl, title, packageName, javaApiExtraction)
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
                                sourceUrl,
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
                replaceAndMarkDocuments(sourceUrl, replacementDocuments);
                continue;
            }

            String extractedText = htmlExtractor.extractCleanContent(sourceDocument);
            String packageName = JavaPackageExtractor.extractPackage(sourceUrl, extractedText);
            ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome =
                    chunkProcessingService.processAndStoreChunks(extractedText, sourceUrl, title, packageName);
            if (chunkingOutcome.generatedNoChunks()) {
                INDEXING_LOG.debug("[INDEXING] No chunks generated for URL");
                continue;
            }
            if (chunkingOutcome.skippedAllChunks()
                    && hybridVectorService.hasExactPointIdsForUrl(
                            QdrantCollectionKind.DOCS,
                            sourceUrl,
                            expectedPointUuids(chunkingOutcome.allChunkHashes()))) {
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
                        chunkProcessingService.processAndStoreChunksForce(extractedText, sourceUrl, title, packageName);
                replacementDocuments = requireCompleteReplacement(replacementChunkingOutcome);
            }
            replaceAndMarkDocuments(sourceUrl, replacementDocuments);
        }
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     */
    @Override
    public IngestionLocalOutcome ingestLocalDirectory(String rootDirectory, FileLimit fileLimit) throws IOException {
        FileLimit requiredFileLimit = Objects.requireNonNull(fileLimit, "fileLimit");
        return localDirectoryIngestionService.ingestLocalDirectory(rootDirectory, requiredFileLimit.maximumFiles());
    }

    private void replaceAndMarkDocuments(
            String sourceUrl, List<org.springframework.ai.document.Document> replacementDocuments) throws IOException {
        List<ReplacementHashRegistration> hashRegistrations =
                requireReplacementHashRegistrations(sourceUrl, replacementDocuments);
        try {
            hybridVectorService.replaceUrlDocuments(QdrantCollectionKind.DOCS, sourceUrl, replacementDocuments);
        } catch (RuntimeException storageException) {
            throw new IOException("Failed to replace documents in Qdrant", storageException);
        }
        markDocumentsIngested(hashRegistrations);
    }

    private void markDocumentsIngested(List<ReplacementHashRegistration> hashRegistrations) throws IOException {
        for (ReplacementHashRegistration hashRegistration : hashRegistrations) {
            localStore.markHashIngested(
                    hashRegistration.contentHash(), hashRegistration.title(), hashRegistration.packageName());
        }
    }

    private static List<ReplacementHashRegistration> requireReplacementHashRegistrations(
            String sourceUrl, List<org.springframework.ai.document.Document> replacementDocuments) {
        List<ReplacementHashRegistration> hashRegistrations = new java.util.ArrayList<>(replacementDocuments.size());
        for (int documentIndex = 0; documentIndex < replacementDocuments.size(); documentIndex++) {
            hashRegistrations.add(requireReplacementHashRegistration(
                    sourceUrl, replacementDocuments.get(documentIndex), documentIndex));
        }
        return List.copyOf(hashRegistrations);
    }

    private static ReplacementHashRegistration requireReplacementHashRegistration(
            String sourceUrl, org.springframework.ai.document.Document replacementDocument, int documentIndex) {
        if (replacementDocument == null) {
            throw invalidReplacementHash(sourceUrl, documentIndex);
        }
        Object hashMetadata = replacementDocument.getMetadata().get(QdrantPayloadFieldSchema.HASH_FIELD);
        if (!(hashMetadata instanceof String contentHash) || contentHash.isBlank()) {
            throw invalidReplacementHash(sourceUrl, documentIndex);
        }
        String title = DocumentFactory.metadataText(replacementDocument, QdrantPayloadFieldSchema.TITLE_FIELD);
        String packageName = DocumentFactory.metadataText(replacementDocument, QdrantPayloadFieldSchema.PACKAGE_FIELD);
        return new ReplacementHashRegistration(contentHash, title, packageName);
    }

    private static IllegalStateException invalidReplacementHash(String sourceUrl, int documentIndex) {
        return new IllegalStateException("Replacement document at index "
                + documentIndex
                + " for source URL "
                + sourceUrl
                + " must contain non-blank string hash metadata");
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
        return new CrawlPageSnapshot(document, rawHtml, List.copyOf(discoveredLinks), url);
    }

    private static String removeUrlFragment(String absoluteHref) {
        int fragmentSeparatorIndex = absoluteHref.indexOf('#');
        return fragmentSeparatorIndex < 0 ? absoluteHref : absoluteHref.substring(0, fragmentSeparatorIndex);
    }

    private CrawlPageSnapshot fetchPageSnapshot(String url) throws IOException {
        String requestedUrl = url;
        for (int redirectCount = 0; redirectCount <= MAXIMUM_CRAWL_REDIRECTS; redirectCount++) {
            crawlBoundary.requireContainsRedirectTarget(requestedUrl);
            org.jsoup.Connection.Response httpResponse = Jsoup.connect(requestedUrl)
                    .timeout((int) HTTP_CONNECT_TIMEOUT.toMillis())
                    .maxBodySize(RESPONSE_BODY_SIZE_PROBE_BYTES)
                    .ignoreContentType(true)
                    .followRedirects(false)
                    .execute();
            if (!isRedirect(httpResponse)) {
                httpResponse.readFully();
                requireSupportedDocumentationContentType(httpResponse.contentType());
                byte[] responseBodyBytes = httpResponse.bodyAsBytes();
                if (responseBodyBytes.length > MAX_RESPONSE_BODY_BYTES) {
                    throw new IOException("Documentation response exceeds maximum body size of "
                            + MAX_RESPONSE_BODY_BYTES
                            + " bytes");
                }
                String finalUrl = httpResponse.url().toExternalForm();
                return prepareCrawlPageSnapshot(finalUrl, httpResponse.body());
            }
            httpResponse.readFully();
            if (redirectCount == MAXIMUM_CRAWL_REDIRECTS) {
                throw new IOException("Documentation response exceeded the redirect limit");
            }
            requestedUrl = resolveRedirectTarget(requestedUrl, httpResponse.header(HTTP_LOCATION_HEADER));
            crawlBoundary.requireContainsRedirectTarget(requestedUrl);
        }
        throw new IOException("Documentation response exceeded the redirect limit");
    }

    private static boolean isRedirect(org.jsoup.Connection.Response httpResponse) {
        int statusCode = httpResponse.statusCode();
        String locationHeader = httpResponse.header(HTTP_LOCATION_HEADER);
        return statusCode >= HTTP_REDIRECT_STATUS_MINIMUM
                && statusCode <= HTTP_REDIRECT_STATUS_MAXIMUM
                && locationHeader != null
                && !locationHeader.isBlank();
    }

    private static void requireSupportedDocumentationContentType(String contentType) throws IOException {
        if (contentType == null) {
            return;
        }
        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        if (!normalizedContentType.startsWith("text/")
                && !normalizedContentType.contains("/xml")
                && !normalizedContentType.contains("+xml")) {
            throw new IOException("Unsupported documentation response content type from "
                    + HTTP_CONTENT_TYPE_HEADER
                    + ": "
                    + contentType);
        }
    }

    private static String resolveRedirectTarget(String sourceUrl, String locationHeader) throws IOException {
        try {
            return new URI(sourceUrl).resolve(locationHeader).normalize().toString();
        } catch (IllegalArgumentException | URISyntaxException invalidRedirectTarget) {
            throw new IOException("Documentation response contained an invalid redirect target", invalidRedirectTarget);
        }
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

    private record CrawlBoundary(String rootUrl, CrawlOrigin origin, String rootPath) {

        private static CrawlBoundary from(String configuredRootUrl) {
            String requiredRootUrl = Objects.requireNonNull(configuredRootUrl, "configuredRootUrl");
            URI rootUri;
            try {
                rootUri = new URI(requiredRootUrl).normalize();
            } catch (URISyntaxException syntaxException) {
                throw new IllegalArgumentException("Configured documentation root URL is invalid", syntaxException);
            }
            if (rootUri.getRawQuery() != null || rootUri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "Configured documentation root URL must not contain a query or fragment");
            }
            CrawlOrigin rootOrigin = CrawlOrigin.from(rootUri)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Configured documentation root URL must use an HTTP(S) origin with a valid port"));
            String normalizedRootPath = normalizedPath(rootUri)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Configured documentation root URL must contain a valid absolute path"));
            return new CrawlBoundary(rootUri.toString(), rootOrigin, stripTrailingPathSeparator(normalizedRootPath));
        }

        private boolean contains(String candidateSourceUrl) {
            if (candidateSourceUrl == null || candidateSourceUrl.isBlank()) {
                return false;
            }
            try {
                URI candidateUri = new URI(candidateSourceUrl).normalize();
                Optional<CrawlOrigin> candidateOrigin = CrawlOrigin.from(candidateUri);
                Optional<String> candidatePath = normalizedPath(candidateUri);
                if (candidateOrigin.isEmpty()
                        || candidatePath.isEmpty()
                        || !origin.equals(candidateOrigin.orElseThrow())) {
                    return false;
                }
                String normalizedCandidatePath = stripTrailingPathSeparator(candidatePath.orElseThrow());
                return "/".equals(rootPath)
                        || normalizedCandidatePath.equals(rootPath)
                        || normalizedCandidatePath.startsWith(rootPath + "/");
            } catch (URISyntaxException syntaxException) {
                return false;
            }
        }

        private void requireContainsRedirectTarget(String finalUrl) throws IOException {
            if (!contains(finalUrl)) {
                throw new IOException("Documentation response redirected outside the configured crawl boundary");
            }
        }

        private static Optional<String> normalizedPath(URI sourceUri) {
            String decodedPath = sourceUri.getPath();
            if (decodedPath == null || decodedPath.isEmpty()) {
                return Optional.of("/");
            }
            if (!decodedPath.startsWith("/") || decodedPath.indexOf('\\') >= 0) {
                return Optional.empty();
            }
            try {
                String normalizedPath =
                        new URI(null, null, decodedPath, null).normalize().getPath();
                return Optional.of(normalizedPath.isEmpty() ? "/" : normalizedPath);
            } catch (URISyntaxException syntaxException) {
                return Optional.empty();
            }
        }

        private static String stripTrailingPathSeparator(String sourcePath) {
            int finalPathCharacterIndex = sourcePath.length() - 1;
            if (sourcePath.length() > 1 && sourcePath.charAt(finalPathCharacterIndex) == '/') {
                return sourcePath.substring(0, finalPathCharacterIndex);
            }
            return sourcePath;
        }
    }

    private record CrawlOrigin(String scheme, String host, int port) {
        private static final int HTTP_DEFAULT_PORT = 80;
        private static final int HTTPS_DEFAULT_PORT = 443;
        private static final int HIGHEST_VALID_PORT = 65_535;

        private static Optional<CrawlOrigin> from(URI sourceUri) {
            String sourceScheme = sourceUri.getScheme();
            String sourceHost = sourceUri.getHost();
            if (!sourceUri.isAbsolute()
                    || sourceUri.getUserInfo() != null
                    || sourceScheme == null
                    || sourceHost == null
                    || sourceHost.isBlank()) {
                return Optional.empty();
            }
            String normalizedScheme = sourceScheme.toLowerCase(Locale.ROOT);
            int defaultPort;
            if ("http".equals(normalizedScheme)) {
                defaultPort = HTTP_DEFAULT_PORT;
            } else if ("https".equals(normalizedScheme)) {
                defaultPort = HTTPS_DEFAULT_PORT;
            } else {
                return Optional.empty();
            }
            int explicitPort = sourceUri.getPort();
            if (explicitPort == 0 || explicitPort > HIGHEST_VALID_PORT) {
                return Optional.empty();
            }
            int effectivePort = explicitPort < 0 ? defaultPort : explicitPort;
            return Optional.of(new CrawlOrigin(normalizedScheme, sourceHost.toLowerCase(Locale.ROOT), effectivePort));
        }
    }

    private record ReplacementHashRegistration(String contentHash, String title, String packageName) {}

    record CrawlPageSnapshot(Document document, String rawHtml, List<String> discoveredLinks, String finalUrl) {}

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
