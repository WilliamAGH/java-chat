package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.service.ingestion.LocalDocsDirectoryIngestionService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
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

        ingestionService.crawlAndIngest(2, crawlPageFetcher);

        assertEquals(List.of(JAVA_API_URL, JAVA_API_QUERY_URL), rootSnapshot.discoveredLinks());
        ArgumentCaptor<String> fetchedUrlCaptor = ArgumentCaptor.forClass(String.class);
        verify(crawlPageFetcher, times(2)).fetch(fetchedUrlCaptor.capture());
        assertEquals(List.of(JAVA_API_URL, JAVA_API_QUERY_URL), fetchedUrlCaptor.getAllValues());
        verify(chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
    }

    @Test
    void mapsJavaApiOverviewAndExactMemberAnchorsToIndependentChunkSegments() {
        JavaApiPageExtraction javaApiExtraction = JavaApiPageExtraction.included(
                "Stream API overview.",
                List.of(new JavaApiAnchoredSection(
                        "map(java.util.function.Function)",
                        "<R> Stream<R> map(Function<? super T,? extends R> mapper)")));

        Optional<ChunkProcessingService.JavaApiPage> javaApiPage = DocsIngestionService.javaApiPageFor(
                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html",
                "Interface Stream<T>",
                "java.util.stream",
                javaApiExtraction);

        List<ChunkProcessingService.JavaApiPageSegment> pageSegments =
                javaApiPage.orElseThrow().segments();
        assertEquals(2, pageSegments.size());
        assertTrue(pageSegments.getFirst().javadocAnchor().isEmpty());
        assertEquals(
                "map(java.util.function.Function)",
                pageSegments.get(1).javadocAnchor().orElseThrow());
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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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
                                "map(java.util.function.Function)",
                                "<R> Stream<R> map(Function<? super T,? extends R> mapper)"))));
        org.springframework.ai.document.Document initialIndexedDocument =
                new org.springframework.ai.document.Document("Stream overview.");
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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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
                indexedJavaApiPage.segments().get(1).javadocAnchor().orElseThrow());
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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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
                                new org.springframework.ai.document.Document("First Stream chunk."),
                                new org.springframework.ai.document.Document("Second Stream chunk.")),
                        List.of("first-chunk-hash", "second-chunk-hash"),
                        2,
                        0));
        DocsIngestionService ingestionService = ingestionServiceFor(JAVA_API_URL);

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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
                new org.springframework.ai.document.Document("Changed collections chunk only.");
        org.springframework.ai.document.Document unchangedReplacementDocument =
                new org.springframework.ai.document.Document("Unchanged collections chunk.");
        org.springframework.ai.document.Document changedReplacementDocument =
                new org.springframework.ai.document.Document("Changed collections chunk.");
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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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
                new org.springframework.ai.document.Document("First collections chunk."),
                new org.springframework.ai.document.Document("Second collections chunk."));
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

        ingestionService.crawlAndIngest(1, crawlPageFetcher);

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

    private DocsIngestionService ingestionServiceFor(String rootUrl) {
        return new DocsIngestionService(
                rootUrl,
                hybridVectorService,
                chunkProcessingService,
                contentHasher,
                localStoreService,
                htmlContentExtractor,
                localDirectoryIngestionService);
    }
}
