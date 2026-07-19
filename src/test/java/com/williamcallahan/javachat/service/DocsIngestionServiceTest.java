package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.williamcallahan.javachat.application.ingestion.PageLimit;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import com.williamcallahan.javachat.service.ingestion.LocalDocsDirectoryIngestionService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Verifies crawl snapshot handling preserves raw HTML and link discovery.
 */
class DocsIngestionServiceTest {

    private static final String JAVA_API_URL =
            "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html";
    private static final String CLASS_USE_URL =
            "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/class-use/List.html";
    private static final String JAVA_API_QUERY_URL = JAVA_API_URL + "?view=all";
    private static final String JAVA_API_TITLE = "Interface Stream<T>";
    private static final int HTTP_OK_STATUS = 200;
    private static final int HTTP_FOUND_STATUS = 302;
    private static final int RESPONSE_BODY_OVERFLOW_BYTES = 2;

    private HybridVectorService hybridVectorService;
    private ChunkProcessingService chunkProcessingService;
    private ContentHasher contentHasher;
    private LocalStoreService localStoreService;
    private HtmlContentExtractor htmlContentExtractor;
    private LocalDocsDirectoryIngestionService localDirectoryIngestionService;

    @BeforeEach
    void setUp() {
        hybridVectorService = mock(HybridVectorService.class);
        chunkProcessingService = mock(ChunkProcessingService.class);
        contentHasher = new ContentHasher();
        localStoreService = mock(LocalStoreService.class);
        htmlContentExtractor = mock(HtmlContentExtractor.class);
        localDirectoryIngestionService = mock(LocalDocsDirectoryIngestionService.class);
    }

    @Test
    void capturesRawHtmlAndDiscoversLinksBeforeMutation() {
        String baseUrl = "https://docs.example.com/root/";
        String html = """
            <html><body>
              <nav><a href="/root/hidden">Hidden</a></nav>
              <main><a href="https://docs.example.com/root/visible">Visible</a></main>
            </body></html>
            """;

        DocsIngestionService.CrawlPageSnapshot snapshot = DocsIngestionService.prepareCrawlPageSnapshot(baseUrl, html);

        List<String> discoveredLinks = snapshot.discoveredLinks();
        assertEquals(html, snapshot.rawHtml(), "Should preserve the raw HTML snapshot");
        assertTrue(discoveredLinks.contains("https://docs.example.com/root/hidden"), "Should include navigation links");
        assertTrue(discoveredLinks.contains("https://docs.example.com/root/visible"), "Should include content links");
    }

    @Test
    void crawlsEachFragmentlessJavaApiUrlOnceWhilePreservingQuery() throws IOException {
        String rootHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page">
              <a href="#map">Map</a>
              <a href="#skip-navbar-top">Skip navigation</a>
              <a href="?view=all#map">Map with query</a>
              <a href="?view=all#skip-navbar-top">Skip with query</a>
            </body></html>
            """;
        String queryHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page"></body></html>
            """;
        DocsIngestionService.CrawlPageSnapshot rootSnapshot =
                DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_URL, rootHtml);
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(JAVA_API_URL)).thenReturn(rootSnapshot);
        when(crawlPageFetcher.fetch(JAVA_API_QUERY_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_QUERY_URL, queryHtml));
        when(htmlContentExtractor.extractJavaApiPage(any())).thenReturn(JavaApiPageExtraction.included("", List.of()));
        when(chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(new PageLimit(2), crawlPageFetcher);

        assertEquals(List.of(JAVA_API_URL, JAVA_API_QUERY_URL), rootSnapshot.discoveredLinks());
        ArgumentCaptor<String> fetchedUrlCaptor = ArgumentCaptor.forClass(String.class);
        verify(crawlPageFetcher, times(2)).fetch(fetchedUrlCaptor.capture());
        assertEquals(List.of(JAVA_API_URL, JAVA_API_QUERY_URL), fetchedUrlCaptor.getAllValues());
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
    }

    @Test
    void rejectsCandidatesOutsideConfiguredOriginPortAndPathBoundary() throws IOException {
        String rootUrl = "https://docs.example.com/root/";
        String allowedChildUrl = rootUrl + "allowed";
        String rootHtml = """
            <html><body>
              <a href="/root/allowed">Allowed child</a>
              <a href="/root-adjacent">Adjacent path</a>
              <a href="/root/%2e%2e/private">Encoded path escape</a>
              <a href="https://docs.example.com.evil/root/host">Deceptive host</a>
              <a href="https://docs.example.com:444/root/port">Different port</a>
              <a href="http://docs.example.com/root/scheme">Different scheme</a>
            </body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(rootUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(rootUrl, rootHtml));
        when(crawlPageFetcher.fetch(allowedChildUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(allowedChildUrl, "<html></html>"));
        when(htmlContentExtractor.extractCleanContent(any())).thenReturn("");
        when(chunkProcessingService.processAndStoreChunks(any(), any(), any(), any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));
        DocsIngestionService ingestionService = ingestionServiceFor(rootUrl);

        ingestionService.crawlAndIngest(new PageLimit(2), crawlPageFetcher);

        ArgumentCaptor<String> fetchedUrlCaptor = ArgumentCaptor.forClass(String.class);
        verify(crawlPageFetcher, times(2)).fetch(fetchedUrlCaptor.capture());
        assertEquals(List.of(rootUrl, allowedChildUrl), fetchedUrlCaptor.getAllValues());
    }

    @Test
    void rejectsRedirectFinalUrlOutsideConfiguredBoundaryBeforePersistence() throws IOException {
        String rootUrl = "https://docs.example.com/root/";
        String offBoundaryFinalUrl = "https://docs.example.com/private/redirected";
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(rootUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(
                        offBoundaryFinalUrl, "<html><body>Redirected</body></html>"));
        DocsIngestionService ingestionService = ingestionServiceFor(rootUrl);

        IOException redirectException = assertThrows(
                IOException.class, () -> ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher));

        assertEquals(
                "Documentation response redirected outside the configured crawl boundary",
                redirectException.getMessage());
        verify(localStoreService, never()).saveHtml(any(), any());
        verify(hybridVectorService, never()).replaceUrlDocuments(any(QdrantCollectionKind.class), any(), any());
    }

    @Test
    void rejectsOffBoundaryRedirectBeforeRequestingTarget() throws IOException {
        AtomicInteger offBoundaryRequestCount = new AtomicInteger();
        HttpServer offBoundaryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        offBoundaryServer.createContext("/private/", httpExchange -> {
            offBoundaryRequestCount.incrementAndGet();
            httpExchange.sendResponseHeaders(HTTP_OK_STATUS, 0);
            httpExchange.close();
        });
        offBoundaryServer.start();

        HttpServer documentationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String offBoundaryUrl =
                "http://127.0.0.1:" + offBoundaryServer.getAddress().getPort() + "/private/redirected";
        documentationServer.createContext("/docs/", httpExchange -> {
            httpExchange.getResponseHeaders().set("Location", offBoundaryUrl);
            httpExchange.sendResponseHeaders(HTTP_FOUND_STATUS, -1);
            httpExchange.close();
        });
        documentationServer.start();
        String rootUrl = "http://127.0.0.1:" + documentationServer.getAddress().getPort() + "/docs/";
        DocsIngestionService ingestionService = ingestionServiceFor(rootUrl);

        try {
            IOException redirectException =
                    assertThrows(IOException.class, () -> ingestionService.crawlAndIngest(new PageLimit(1)));

            assertEquals(
                    "Documentation response redirected outside the configured crawl boundary",
                    redirectException.getMessage());
            assertEquals(0, offBoundaryRequestCount.get());
            verify(localStoreService, never()).saveHtml(any(), any());
        } finally {
            documentationServer.stop(0);
            offBoundaryServer.stop(0);
        }
    }

    @Test
    void rejectsTruncatedOversizedResponseBeforePersistence() throws IOException {
        byte[] oversizedResponseBody =
                new byte[DocsIngestionService.MAX_RESPONSE_BODY_BYTES + RESPONSE_BODY_OVERFLOW_BYTES];
        HttpServer documentationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        documentationServer.createContext("/docs/", httpExchange -> {
            httpExchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            httpExchange.sendResponseHeaders(HTTP_OK_STATUS, oversizedResponseBody.length);
            try (OutputStream responseBodyStream = httpExchange.getResponseBody()) {
                responseBodyStream.write(oversizedResponseBody);
            }
        });
        documentationServer.start();
        String rootUrl = "http://127.0.0.1:" + documentationServer.getAddress().getPort() + "/docs/";
        DocsIngestionService ingestionService = ingestionServiceFor(rootUrl);

        try {
            IOException oversizedResponseException =
                    assertThrows(IOException.class, () -> ingestionService.crawlAndIngest(new PageLimit(1)));

            assertEquals(
                    "Documentation response exceeds maximum body size of "
                            + DocsIngestionService.MAX_RESPONSE_BODY_BYTES
                            + " bytes",
                    oversizedResponseException.getMessage());
            verify(localStoreService, never()).saveHtml(any(), any());
            verify(hybridVectorService, never()).replaceUrlDocuments(any(QdrantCollectionKind.class), any(), any());
        } finally {
            documentationServer.stop(0);
        }
    }

    @Test
    void mapsJavaApiOverviewAndExactMemberAnchorsToIndependentChunkSegments() {
        JavaApiPageExtraction javaApiExtraction = JavaApiPageExtraction.included(
                "Stream API overview.",
                List.of(new JavaApiAnchoredSection(
                        new JavadocMemberAnchor("map(java.util.function.Function)"),
                        "<R> Stream<R> map(Function<? super T,? extends R> mapper)")));

        Optional<ChunkProcessingService.JavaApiPage> javaApiPage = DocsIngestionService.javaApiPageFor(
                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html",
                "Interface Stream<T>",
                "java.util.stream",
                javaApiExtraction);

        List<ChunkProcessingService.JavaApiPageSegment> pageSegments =
                javaApiPage.orElseThrow().segments();
        assertEquals(2, pageSegments.size());
        assertTrue(pageSegments.getFirst().javadocMemberAnchor().isEmpty());
        assertEquals(
                "map(java.util.function.Function)",
                pageSegments.get(1).javadocMemberAnchor().orElseThrow().domIdentifier());
    }

    @Test
    void excludesClassUsePageFromChunkingContract() {
        Optional<ChunkProcessingService.JavaApiPage> javaApiPage = DocsIngestionService.javaApiPageFor(
                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/class-use/List.html",
                "Uses of Interface java.util.List",
                "java.util",
                JavaApiPageExtraction.excludedClassUsePage());

        assertTrue(javaApiPage.isEmpty());
    }

    @Test
    void deletesClassUseVectorsBeforeSkippingIndexContentButPreservesSnapshot() throws IOException {
        String classUseHtml = """
            <html><head><title>Uses of Interface java.util.List</title></head>
            <body class="class-use-page"><a href="Child.html">Child</a></body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(CLASS_USE_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(CLASS_USE_URL, classUseHtml));
        when(htmlContentExtractor.extractJavaApiPage(any())).thenReturn(JavaApiPageExtraction.excludedClassUsePage());
        DocsIngestionService ingestionService = ingestionServiceFor(CLASS_USE_URL);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        verify(localStoreService).saveHtml(CLASS_USE_URL, classUseHtml);
        InOrder extractionAndDeletionOrder = inOrder(htmlContentExtractor, hybridVectorService);
        extractionAndDeletionOrder.verify(htmlContentExtractor).extractJavaApiPage(any());
        extractionAndDeletionOrder.verify(hybridVectorService).deleteByUrl(QdrantCollectionKind.DOCS, CLASS_USE_URL);
        verify(chunkProcessingService, never()).processAndStoreJavaApiPage(any());
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
        verify(hybridVectorService, never()).upsert(eq(QdrantCollectionKind.DOCS), any());
    }

    @Test
    void replacesFreshJavaApiPageWithInitialCompleteDocumentsWithoutForcedChunking() throws IOException {
        String javaApiHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page"><a href="Another.html">Another</a></body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(JAVA_API_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_URL, javaApiHtml));
        when(htmlContentExtractor.extractJavaApiPage(any()))
                .thenReturn(JavaApiPageExtraction.included(
                        "Stream overview.",
                        List.of(new JavaApiAnchoredSection(
                                new JavadocMemberAnchor("map(java.util.function.Function)"),
                                "<R> Stream<R> map(Function<? super T,? extends R> mapper)"))));
        org.springframework.ai.document.Document initialIndexedDocument =
                replacementDocument("Stream overview.", "chunk-hash");
        when(chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(initialIndexedDocument), List.of("chunk-hash"), 1, 0));
        doAnswer(invocation -> {
                    List<org.springframework.ai.document.Document> indexedDocuments = invocation.getArgument(2);
                    assertEquals(List.of(initialIndexedDocument), indexedDocuments);
                    assertEquals(
                            DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE,
                            indexedDocuments.getFirst().getMetadata().get(QdrantPayloadFieldSchema.DOC_TYPE_FIELD));
                    return null;
                })
                .when(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(JAVA_API_URL), any());
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        ArgumentCaptor<ChunkProcessingService.JavaApiPage> javaApiPageCaptor =
                ArgumentCaptor.forClass(ChunkProcessingService.JavaApiPage.class);
        InOrder persistenceOrder = inOrder(chunkProcessingService, hybridVectorService);
        persistenceOrder.verify(chunkProcessingService).processAndStoreJavaApiPage(javaApiPageCaptor.capture());
        persistenceOrder
                .verify(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(JAVA_API_URL), any());
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
        ChunkProcessingService.JavaApiPage indexedJavaApiPage = javaApiPageCaptor.getValue();
        assertEquals(JAVA_API_URL, indexedJavaApiPage.sourceUrl());
        assertEquals(JAVA_API_TITLE, indexedJavaApiPage.title());
        assertEquals("java.util.stream", indexedJavaApiPage.packageName());
        assertEquals(
                "map(java.util.function.Function)",
                indexedJavaApiPage
                        .segments()
                        .get(1)
                        .javadocMemberAnchor()
                        .orElseThrow()
                        .domIdentifier());
    }

    @Test
    void retainsPriorJavaApiVectorsWhenExtractionProducesNoChunks() throws IOException {
        String javaApiHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page"></body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(JAVA_API_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_URL, javaApiHtml));
        when(htmlContentExtractor.extractJavaApiPage(any())).thenReturn(JavaApiPageExtraction.included("", List.of()));
        when(chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        verify(chunkProcessingService).processAndStoreJavaApiPage(any());
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
        verify(hybridVectorService, never()).deleteByUrl(QdrantCollectionKind.DOCS, JAVA_API_URL);
        verify(hybridVectorService, never()).upsert(eq(QdrantCollectionKind.DOCS), any());
        verify(hybridVectorService, never())
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(JAVA_API_URL), any());
    }

    @Test
    void retainsCompleteUnchangedJavaApiCorpusWithoutReembedding() throws IOException {
        String javaApiHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page"></body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(JAVA_API_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_URL, javaApiHtml));
        when(htmlContentExtractor.extractJavaApiPage(any()))
                .thenReturn(JavaApiPageExtraction.included("Stream overview.", List.of()));
        when(chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(), List.of("unchanged-chunk-hash"), 1, 1));
        List<String> unchangedPointUuids = List.of(contentHasher.uuidFromHash("unchanged-chunk-hash"));
        when(hybridVectorService.hasExactPointIdsForUrl(QdrantCollectionKind.DOCS, JAVA_API_URL, unchangedPointUuids))
                .thenReturn(true);
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        verify(chunkProcessingService).processAndStoreJavaApiPage(any());
        verify(hybridVectorService)
                .hasExactPointIdsForUrl(QdrantCollectionKind.DOCS, JAVA_API_URL, unchangedPointUuids);
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
        verify(hybridVectorService, never()).deleteByUrl(QdrantCollectionKind.DOCS, JAVA_API_URL);
        verify(hybridVectorService, never()).upsert(eq(QdrantCollectionKind.DOCS), any());
        verify(hybridVectorService, never())
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(JAVA_API_URL), any());
    }

    @Test
    void replacesRevertedJavaApiPageWhenEqualSizedStoredCorpusHasDifferentPointIds() throws IOException {
        String javaApiHtml = """
            <html><head><title>Interface Stream&lt;T&gt;</title></head>
            <body class="class-declaration-page"></body></html>
            """;
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(JAVA_API_URL))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(JAVA_API_URL, javaApiHtml));
        when(htmlContentExtractor.extractJavaApiPage(any()))
                .thenReturn(JavaApiPageExtraction.included("Stream overview.", List.of()));
        when(chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(), List.of("first-chunk-hash", "second-chunk-hash"), 2, 2));
        List<String> revertedPointUuids = List.of(
                contentHasher.uuidFromHash("first-chunk-hash"), contentHasher.uuidFromHash("second-chunk-hash"));
        when(hybridVectorService.hasExactPointIdsForUrl(QdrantCollectionKind.DOCS, JAVA_API_URL, revertedPointUuids))
                .thenReturn(false);
        when(chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(
                                replacementDocument("First Stream chunk.", "first-chunk-hash"),
                                replacementDocument("Second Stream chunk.", "second-chunk-hash")),
                        List.of("first-chunk-hash", "second-chunk-hash"),
                        2,
                        0));
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        InOrder replacementOrder = inOrder(chunkProcessingService, hybridVectorService);
        replacementOrder.verify(chunkProcessingService).processAndStoreJavaApiPage(any());
        replacementOrder
                .verify(hybridVectorService)
                .hasExactPointIdsForUrl(QdrantCollectionKind.DOCS, JAVA_API_URL, revertedPointUuids);
        replacementOrder.verify(chunkProcessingService).processAndStoreJavaApiPageForce(any());
        replacementOrder
                .verify(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(JAVA_API_URL), any());
    }

    @Test
    void replacesCompleteOrdinaryUrlCorpusWhenOnlySomeChunksChanged() throws IOException {
        String ordinaryUrl = "https://docs.example.com/guides/collections";
        String ordinaryHtml = """
            <html><head><title>Collections guide</title></head>
            <body><main>Current collections guidance.</main></body></html>
            """;
        String extractedText = "Current collections guidance.";
        org.springframework.ai.document.Document partialChangedDocument =
                replacementDocument("Changed collections chunk only.", "changed-collections-hash");
        org.springframework.ai.document.Document unchangedReplacementDocument =
                replacementDocument("Unchanged collections chunk.", "unchanged-collections-hash");
        org.springframework.ai.document.Document changedReplacementDocument =
                replacementDocument("Changed collections chunk.", "changed-collections-hash");
        List<org.springframework.ai.document.Document> completeReplacementDocuments =
                List.of(unchangedReplacementDocument, changedReplacementDocument);
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(ordinaryUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(ordinaryUrl, ordinaryHtml));
        when(htmlContentExtractor.extractCleanContent(any())).thenReturn(extractedText);
        when(chunkProcessingService.processAndStoreChunks(extractedText, ordinaryUrl, "Collections guide", ""))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(partialChangedDocument),
                        List.of("unchanged-collections-hash", "changed-collections-hash"),
                        2,
                        1));
        when(chunkProcessingService.processAndStoreChunksForce(extractedText, ordinaryUrl, "Collections guide", ""))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        completeReplacementDocuments,
                        List.of("unchanged-collections-hash", "changed-collections-hash"),
                        2,
                        0));
        doAnswer(invocation -> {
                    List<org.springframework.ai.document.Document> replacementDocuments = invocation.getArgument(2);
                    assertEquals(completeReplacementDocuments, replacementDocuments);
                    return null;
                })
                .when(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(ordinaryUrl), any());
        DocsIngestionService ingestionService = ingestionServiceFor(ordinaryUrl);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        InOrder replacementOrder = inOrder(chunkProcessingService, hybridVectorService);
        replacementOrder
                .verify(chunkProcessingService)
                .processAndStoreChunks(extractedText, ordinaryUrl, "Collections guide", "");
        replacementOrder
                .verify(chunkProcessingService)
                .processAndStoreChunksForce(extractedText, ordinaryUrl, "Collections guide", "");
        replacementOrder
                .verify(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(ordinaryUrl), any());
        verify(hybridVectorService, never()).upsert(eq(QdrantCollectionKind.DOCS), any());
    }

    @Test
    void replacesFreshOrdinaryUrlWithInitialCompleteDocumentsWithoutForcedChunking() throws IOException {
        String ordinaryUrl = "https://docs.example.com/guides/collections";
        String ordinaryHtml = """
            <html><head><title>Collections guide</title></head>
            <body><main>Current collections guidance.</main></body></html>
            """;
        String extractedText = "Current collections guidance.";
        List<org.springframework.ai.document.Document> initialDocuments = List.of(
                replacementDocument("First collections chunk.", "first-collections-hash"),
                replacementDocument("Second collections chunk.", "second-collections-hash"));
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(ordinaryUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(ordinaryUrl, ordinaryHtml));
        when(htmlContentExtractor.extractCleanContent(any())).thenReturn(extractedText);
        when(chunkProcessingService.processAndStoreChunks(extractedText, ordinaryUrl, "Collections guide", ""))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        initialDocuments, List.of("first-collections-hash", "second-collections-hash"), 2, 0));
        doAnswer(invocation -> {
                    List<org.springframework.ai.document.Document> replacementDocuments = invocation.getArgument(2);
                    assertEquals(initialDocuments, replacementDocuments);
                    return null;
                })
                .when(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(ordinaryUrl), any());
        DocsIngestionService ingestionService = ingestionServiceFor(ordinaryUrl);

        ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher);

        InOrder replacementOrder = inOrder(chunkProcessingService, hybridVectorService);
        replacementOrder
                .verify(chunkProcessingService)
                .processAndStoreChunks(extractedText, ordinaryUrl, "Collections guide", "");
        replacementOrder
                .verify(hybridVectorService)
                .replaceUrlDocuments(eq(QdrantCollectionKind.DOCS), eq(ordinaryUrl), any());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(extractedText, ordinaryUrl, "Collections guide", "");
        verify(hybridVectorService, never()).upsert(eq(QdrantCollectionKind.DOCS), any());
    }

    @Test
    void rejectsAnyReplacementDocumentWithoutHashBeforeQdrantMutation() throws IOException {
        String ordinaryUrl = "https://docs.example.com/guides/collections";
        String ordinaryHtml = """
            <html><head><title>Collections guide</title></head>
            <body><main>Current collections guidance.</main></body></html>
            """;
        String extractedText = "Current collections guidance.";
        org.springframework.ai.document.Document validReplacementDocument =
                replacementDocument("First collections chunk.", "first-collections-hash");
        org.springframework.ai.document.Document missingHashReplacementDocument =
                new org.springframework.ai.document.Document("Second collections chunk.");
        DocsIngestionService.CrawlPageFetcher crawlPageFetcher = mock(DocsIngestionService.CrawlPageFetcher.class);
        when(crawlPageFetcher.fetch(ordinaryUrl))
                .thenReturn(DocsIngestionService.prepareCrawlPageSnapshot(ordinaryUrl, ordinaryHtml));
        when(htmlContentExtractor.extractCleanContent(any())).thenReturn(extractedText);
        when(chunkProcessingService.processAndStoreChunks(extractedText, ordinaryUrl, "Collections guide", ""))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(validReplacementDocument, missingHashReplacementDocument),
                        List.of("first-collections-hash", "second-collections-hash"),
                        2,
                        0));
        DocsIngestionService ingestionService = ingestionServiceFor(ordinaryUrl);

        IllegalStateException missingHashException = assertThrows(
                IllegalStateException.class, () -> ingestionService.crawlAndIngest(new PageLimit(1), crawlPageFetcher));

        assertEquals(
                "Replacement document at index 1 for source URL "
                        + ordinaryUrl
                        + " must contain non-blank string hash metadata",
                missingHashException.getMessage());
        verify(hybridVectorService, never()).replaceUrlDocuments(any(QdrantCollectionKind.class), any(), any());
        verify(localStoreService, never()).markHashIngested(any(), any(), any());
    }

    private DocsIngestionService ingestionServiceFor(String rootUrl) {
        AppProperties appProperties = new AppProperties();
        appProperties.getDocs().setRootUrl(rootUrl);
        return new DocsIngestionService(
                appProperties,
                hybridVectorService,
                chunkProcessingService,
                contentHasher,
                localStoreService,
                htmlContentExtractor,
                localDirectoryIngestionService);
    }

    private static org.springframework.ai.document.Document replacementDocument(
            String documentText, String contentHash) {
        org.springframework.ai.document.Document replacementDocument =
                new org.springframework.ai.document.Document(documentText);
        replacementDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, contentHash);
        return replacementDocument;
    }
}
