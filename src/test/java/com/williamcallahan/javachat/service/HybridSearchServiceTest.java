package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Verifies hybrid and sparse citation retrieval behavior at the direct Qdrant request boundary.
 */
class HybridSearchServiceTest {

    private static final Logger HYBRID_SEARCH_LOGGER = (Logger) LoggerFactory.getLogger(HybridSearchService.class);
    private static final String COLLECTION_FAILURE_WARNING =
            "[QDRANT] Search failed for collection=java-chat-qwen3-embedding-4b-2560-books (exceptionType=RuntimeException)";
    private static final String CITATION_COLLECTION_FAILURE_WARNING =
            "[QDRANT] Search failed for collection=java-chat-qwen3-embedding-4b-2560-docs (exceptionType=RuntimeException)";
    private static final DocsSourceRegistry.JavaApiDocumentationSource REPRESENTED_JAVA_API_SOURCE =
            DocsSourceRegistry.javaApiDocumentationSources().getFirst();
    private static final List<String> OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES =
            DocsSourceRegistry.officialDocumentationSourceIdentities();
    private static final String HYBRID_QUERY = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " streams";
    private static final String SECOND_HYBRID_QUERY = "Java records and sealed classes";
    private static final String CITATION_QUERY = "Java records";
    private static final String EXACT_JAVA_API_QUERY = "What does List.of(E, E) return?";
    private static final String RUNTIME_VALUE_JAVA_API_QUERY =
            "What does Java 25 List.of(firstValue, secondValue) return?";
    private static final String VERSIONED_SELECTOR_CITATION_QUERY =
            "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " List.of(E, E)";
    private static final Duration DISPATCH_BUDGET_TEST_TIMEOUT = Duration.ofMillis(500);
    private static final Duration EXHAUSTED_DISPATCH_QUERY_TIMEOUT = Duration.ofMillis(5);
    private static final Duration EXHAUSTED_DISPATCH_BLOCKING_DURATION = Duration.ofMillis(25);
    private static final Duration SHARED_QUERY_TIMEOUT = Duration.ofMillis(150);
    private static final Duration SHARED_DEADLINE_ASSERTION_LIMIT = Duration.ofMillis(450);
    private static final Duration GENEROUS_QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STAGE_BUDGET_TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration NEARLY_EXHAUSTED_STAGE_BUDGET = Duration.ofMillis(300);
    private static final int TEST_DENSE_PREFETCH_HNSW_EF = 37;
    private static final UUID SCORED_POINT_UUID = UUID.fromString("97c1f646-bd04-443e-a29f-e0283fe27e5b");

    private QdrantClient qdrantClient;
    private EmbeddingClient embeddingClient;
    private LexicalSparseVectorEncoder sparseEncoder;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        embeddingClient = mock(EmbeddingClient.class);
        sparseEncoder = mock(LexicalSparseVectorEncoder.class);
        appProperties = new AppProperties();
    }

    @Test
    void appliesServerFilterToHybridPrefetchAndDispatchesEachQueryWithTheConfiguredDuration() {
        appProperties.getQdrant().setRrfK(77);
        appProperties.getQdrant().setDensePrefetchHnswEf(TEST_DENSE_PREFETCH_HNSW_EF);
        appProperties.getQdrant().setQueryTimeout(DISPATCH_BUDGET_TEST_TIMEOUT);

        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L, 3L), List.of(2.0f, 1.0f)));

        List<QueryPoints> capturedQueries = new ArrayList<>();
        List<Duration> capturedQueryTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    capturedQueryTimeouts.add(invocation.getArgument(1));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint retrievalConstraint =
                RetrievalConstraint.forDocVersions(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease()));

        hybridSearchService.search(HYBRID_QUERY, 5, retrievalConstraint, stageDeadlineNanos());

        assertEquals(4, capturedQueries.size());
        assertTrue(capturedQueryTimeouts.stream()
                .allMatch(queryTimeout -> !queryTimeout.isZero()
                        && !queryTimeout.isNegative()
                        && queryTimeout.compareTo(DISPATCH_BUDGET_TEST_TIMEOUT) <= 0));
        assertTrue(capturedQueryTimeouts.getLast().compareTo(capturedQueryTimeouts.getFirst()) < 0);
        QueryPoints capturedQuery = capturedQueries.getFirst();
        assertEquals(77, capturedQuery.getQuery().getRrf().getK());
        assertTrue(capturedQuery.hasFilter());
        assertTrue(capturedQuery.getFilter().toString().contains(QdrantPayloadFieldSchema.DOC_VERSION_FIELD));
        assertFalse(capturedQuery.getPrefetchList().isEmpty());
        PrefetchQuery densePrefetch = capturedQuery.getPrefetch(0);
        assertTrue(densePrefetch.hasFilter());
        assertTrue(densePrefetch.hasParams());
        assertEquals(TEST_DENSE_PREFETCH_HNSW_EF, densePrefetch.getParams().getHnswEf());
        assertFalse(densePrefetch.getParams().getExact());
        PrefetchQuery sparsePrefetch = capturedQuery.getPrefetch(1);
        assertTrue(sparsePrefetch.hasFilter());
        assertFalse(sparsePrefetch.hasParams());
        verify(qdrantClient, never()).queryAsync(any(QueryPoints.class));
    }

    @Test
    void primaryHybridSearchDoesNotTreatProjectTypeSyntaxAsAnOfficialJavadocConstraint() {
        when(embeddingClient.embed(eq(EXACT_JAVA_API_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(EXACT_JAVA_API_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of());
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        buildSearchService().search(EXACT_JAVA_API_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos());

        assertEquals(4, capturedQueries.size());
        for (QueryPoints capturedQuery : capturedQueries) {
            assertFalse(capturedQuery.hasFilter());
            for (PrefetchQuery prefetchQuery : capturedQuery.getPrefetchList()) {
                assertFalse(prefetchQuery.hasFilter());
            }
        }
    }

    @Test
    void multipleVersionScopesShareOneEncodingAndRetainIndependentResultBudgets() {
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
        QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery = mock(QdrantGitHubCollectionDiscovery.class);
        when(gitHubCollectionDiscovery.getDiscoveredCollections()).thenReturn(List.of("github-example-project"));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);

        List<List<Document>> versionOutcomes = buildSearchServiceWithGitHubDiscovery(gitHubCollectionDiscovery)
                .searchByConstraint(
                        HYBRID_QUERY,
                        5,
                        List.of(
                                officialDocumentationConstraint.withDocVersions(List.of("21")),
                                officialDocumentationConstraint.withDocVersions(List.of("24"))),
                        stageDeadlineNanos());

        assertEquals(2, versionOutcomes.size());
        assertEquals(1, versionOutcomes.get(0).size());
        assertEquals(1, versionOutcomes.get(1).size());
        assertEquals(4, capturedQueries.size());
        assertTrue(capturedQueries.subList(0, 2).stream()
                .allMatch(queryRequest -> queryRequest.getLimit() == 5
                        && queryRequest.getFilter().toString().contains("21")));
        assertTrue(capturedQueries.subList(2, 4).stream()
                .allMatch(queryRequest -> queryRequest.getLimit() == 5
                        && queryRequest.getFilter().toString().contains("24")));
        assertTrue(capturedQueries.stream().allMatch(queryRequest -> List.of(
                        appProperties.getQdrant().getCollections().getDocs(),
                        appProperties.getQdrant().getCollections().getPdfs())
                .contains(queryRequest.getCollectionName())));
        verify(embeddingClient, times(1)).embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class));
        verify(sparseEncoder, times(1)).encode(HYBRID_QUERY);
        verify(gitHubCollectionDiscovery, never()).getDiscoveredCollections();
    }

    @Test
    void concurrentSearchesDispatchTheirQdrantFanOutsIndependently()
            throws InterruptedException, ExecutionException, TimeoutException {
        appProperties.getQdrant().setQueryTimeout(GENEROUS_QUERY_TIMEOUT);
        int collectionCount = appProperties.getQdrant().getCollections().all().size();
        CountDownLatch allFanOutsDispatched = new CountDownLatch(collectionCount * 2);
        List<SettableFuture<List<ScoredPoint>>> heldQueryFutures = new CopyOnWriteArrayList<>();
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(embeddingClient.embed(eq(SECOND_HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.3f, 0.2f, 0.1f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        when(sparseEncoder.encode(SECOND_HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));
        doAnswer(invocation -> {
                    SettableFuture<List<ScoredPoint>> heldQueryFuture = SettableFuture.create();
                    heldQueryFutures.add(heldQueryFuture);
                    allFanOutsDispatched.countDown();
                    return heldQueryFuture;
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
        HybridSearchService hybridSearchService = buildSearchService();

        try (ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<Document>> firstSearch = CompletableFuture.supplyAsync(
                    () -> hybridSearchService.search(HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()),
                    searchExecutor);
            CompletableFuture<List<Document>> secondSearch = CompletableFuture.supplyAsync(
                    () -> hybridSearchService.search(
                            SECOND_HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()),
                    searchExecutor);
            try {
                assertTrue(allFanOutsDispatched.await(1, TimeUnit.SECONDS));
                assertFalse(firstSearch.isDone());
                assertFalse(secondSearch.isDone());

                heldQueryFutures.forEach(heldQueryFuture -> heldQueryFuture.set(List.of(scoredPoint())));

                assertEquals(1, firstSearch.get(1, TimeUnit.SECONDS).size());
                assertEquals(1, secondSearch.get(1, TimeUnit.SECONDS).size());
            } finally {
                heldQueryFutures.forEach(heldQueryFuture -> heldQueryFuture.set(List.of(scoredPoint())));
            }
        }
    }

    @Test
    void nearlyExhaustedStageDeadlineTightensEmbeddingAndQdrantBudgetsBelowTheirConfiguredCaps() {
        appProperties.getQdrant().setQueryTimeout(GENEROUS_QUERY_TIMEOUT);
        List<Duration> capturedEmbeddingBudgets = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedEmbeddingBudgets.add(invocation.getArgument(2));
                    return new float[] {0.1f, 0.2f, 0.3f};
                })
                .when(embeddingClient)
                .embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class));
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        List<Duration> capturedQueryTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueryTimeouts.add(invocation.getArgument(1));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        long nearlyExhaustedStageDeadlineNanos = System.nanoTime() + NEARLY_EXHAUSTED_STAGE_BUDGET.toNanos();
        List<Document> nearlyExhaustedStageOutcome = buildSearchService()
                .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), nearlyExhaustedStageDeadlineNanos);

        assertEquals(1, nearlyExhaustedStageOutcome.size());
        assertEquals(1, capturedEmbeddingBudgets.size());
        Duration capturedEmbeddingBudget = capturedEmbeddingBudgets.getFirst();
        assertFalse(capturedEmbeddingBudget.isNegative());
        assertTrue(capturedEmbeddingBudget.compareTo(NEARLY_EXHAUSTED_STAGE_BUDGET) <= 0);
        assertFalse(capturedQueryTimeouts.isEmpty());
        assertTrue(capturedQueryTimeouts.stream()
                .allMatch(dispatchBudget -> !dispatchBudget.isZero()
                        && !dispatchBudget.isNegative()
                        && dispatchBudget.compareTo(NEARLY_EXHAUSTED_STAGE_BUDGET) <= 0));
    }

    @Test
    void collectionTimeoutReportsEffectiveStageBudgetInsteadOfConfiguredFullTimeout() {
        appProperties.getQdrant().setQueryTimeout(GENEROUS_QUERY_TIMEOUT);
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        doAnswer(invocation -> SettableFuture.<List<ScoredPoint>>create())
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        long nearlyExhaustedStageDeadlineNanos = System.nanoTime() + NEARLY_EXHAUSTED_STAGE_BUDGET.toNanos();
        HybridSearchPartialFailureException timedOutSearchFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            timedOutSearchFailure = assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                    .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), nearlyExhaustedStageDeadlineNanos));

            assertEquals(
                    appProperties.getQdrant().getCollections().all().size(),
                    expectedLogEvents.events().size());
        }

        assertInstanceOf(TimeoutException.class, timedOutSearchFailure.getCause());
        assertTrue(timedOutSearchFailure.collectionFailures().stream()
                .map(HybridSearchPartialFailureException.CollectionSearchFailure::failureDetails)
                .allMatch(failureDetails -> failureDetails.contains("Qdrant query exceeded timeout")));
        assertTrue(timedOutSearchFailure.collectionFailures().stream()
                .map(HybridSearchPartialFailureException.CollectionSearchFailure::failureDetails)
                .noneMatch(failureDetails -> failureDetails.contains(GENEROUS_QUERY_TIMEOUT.toMillis() + "ms")));
    }

    @Test
    void citationSearchUsesOnlyTheSparseOfficialDocumentationRequestAndDurationOverload() {
        appProperties.getQdrant().setSparseVectorName("bm25");
        when(sparseEncoder.encode(CITATION_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L, 7L), List.of(3.0f, 1.0f)));
        QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery = mock(QdrantGitHubCollectionDiscovery.class);
        when(gitHubCollectionDiscovery.getDiscoveredCollections()).thenReturn(List.of("github-example-project"));

        List<QueryPoints> capturedQueries = new ArrayList<>();
        List<Duration> capturedQueryTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    capturedQueryTimeouts.add(invocation.getArgument(1));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchService hybridSearchService = buildSearchServiceWithGitHubDiscovery(gitHubCollectionDiscovery);
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);

        List<Document> citationSearchOutcome = hybridSearchService.searchDocumentationCitations(
                CITATION_QUERY, 3, officialDocumentationConstraint, stageDeadlineNanos());

        assertEquals(2, capturedQueries.size());
        assertEquals(2, capturedQueryTimeouts.size());
        Duration citationQueryTimeout = capturedQueryTimeouts.getFirst();
        assertFalse(citationQueryTimeout.isZero());
        assertFalse(citationQueryTimeout.isNegative());
        assertTrue(citationQueryTimeout.compareTo(appProperties.getQdrant().getQueryTimeout()) <= 0);
        assertEquals(
                List.of(
                        appProperties.getQdrant().getCollections().getDocs(),
                        appProperties.getQdrant().getCollections().getPdfs()),
                capturedQueries.stream().map(QueryPoints::getCollectionName).toList());
        for (QueryPoints citationQuery : capturedQueries) {
            assertEquals("bm25", citationQuery.getUsing());
            assertEquals(3, citationQuery.getLimit());
            assertFullRetrievalPayloadSelector(
                    citationQuery.getWithPayload().getInclude().getFieldsList());
            assertEquals(0, citationQuery.getPrefetchCount());
            assertTrue(citationQuery.getQuery().hasNearest());
            assertFalse(citationQuery.getQuery().hasRrf());
            assertTrue(citationQuery.getQuery().getNearest().hasSparse());
            assertEquals(
                    List.of(2, 7),
                    citationQuery.getQuery().getNearest().getSparse().getIndicesList());
            assertTrue(citationQuery.hasFilter());
            String officialFilter = citationQuery.getFilter().toString();
            assertTrue(officialFilter.contains(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD));
            assertTrue(officialFilter.contains("official"));
            assertTrue(officialFilter.contains(QdrantPayloadFieldSchema.DOC_SET_FIELD));
            assertTrue(officialFilter.contains(REPRESENTED_JAVA_API_SOURCE.relativeMirrorPath()));
        }
        assertEquals(1, citationSearchOutcome.size());
        assertEquals(
                appProperties.getQdrant().getCollections().getDocs(),
                citationSearchOutcome.getFirst().getMetadata().get("collection"));
        verifyNoInteractions(embeddingClient);
        verify(gitHubCollectionDiscovery, never()).getDiscoveredCollections();
        verify(qdrantClient, never()).queryAsync(any(QueryPoints.class));
    }

    @Test
    void exactCitationSearchScrollsByPayloadFilterWithoutSparseScoring() {
        List<ScrollPoints> capturedScrolls = new ArrayList<>();
        List<Duration> capturedScrollTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedScrolls.add(invocation.getArgument(0));
                    capturedScrollTimeouts.add(invocation.getArgument(1));
                    return Futures.immediateFuture(ScrollResponse.newBuilder()
                            .addResult(retrievedPoint())
                            .build());
                })
                .when(qdrantClient)
                .scrollAsync(notNull(), notNull());

        HybridSearchService hybridSearchService = buildSearchService();
        List<Document> exactCitationOutcome = hybridSearchService.searchDocumentationCitations(
                VERSIONED_SELECTOR_CITATION_QUERY,
                3,
                RetrievalConstraint.forDocVersions(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease())),
                stageDeadlineNanos());

        assertEquals(1, capturedScrolls.size());
        assertEquals(1, capturedScrollTimeouts.size());
        ScrollPoints exactCitationScroll = capturedScrolls.getFirst();
        assertEquals(appProperties.getQdrant().getCollections().getDocs(), exactCitationScroll.getCollectionName());
        assertEquals(3, exactCitationScroll.getLimit());
        assertFullRetrievalPayloadSelector(
                exactCitationScroll.getWithPayload().getInclude().getFieldsList());
        assertTrue(exactCitationScroll.hasFilter());
        String versionFilter = exactCitationScroll.getFilter().toString();
        assertTrue(versionFilter.contains(QdrantPayloadFieldSchema.DOC_VERSION_FIELD));
        assertTrue(versionFilter.contains(REPRESENTED_JAVA_API_SOURCE.javaRelease()));
        assertTrue(versionFilter.contains(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD));
        assertTrue(versionFilter.contains("List.html"));
        assertTrue(versionFilter.contains(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertTrue(versionFilter.contains("of(E,E)"));
        assertEquals(1, exactCitationOutcome.size());
        assertEquals(
                appProperties.getQdrant().getCollections().getDocs(),
                exactCitationOutcome.getFirst().getMetadata().get("collection"));
        verifyNoInteractions(sparseEncoder);
        verifyNoInteractions(embeddingClient);
        verify(qdrantClient, never()).queryAsync(notNull(), notNull());
    }

    @Test
    void exactThirdPartySignatureRemainsOnSparseOfficialDocumentationSearch() {
        String thirdPartyQuery = "Explain SpringApplication.run(java.lang.String)";
        when(sparseEncoder.encode(thirdPartyQuery + " SpringApplication run"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L, 7L), List.of(3.0f, 1.0f)));
        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        List<Document> citationOutcome = buildSearchService()
                .searchDocumentationCitations(thirdPartyQuery, 3, RetrievalConstraint.none(), stageDeadlineNanos());

        assertEquals(2, capturedQueries.size());
        assertEquals(1, citationOutcome.size());
        verify(sparseEncoder).encode(thirdPartyQuery + " SpringApplication run");
        verify(qdrantClient, never()).scrollAsync(notNull(), notNull());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void runtimeValueCitationQueryRemainsSparse() {
        when(sparseEncoder.encode("List of"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L, 7L), List.of(3.0f, 1.0f)));
        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        List<Document> citationOutcome = buildSearchService()
                .searchDocumentationCitations(
                        RUNTIME_VALUE_JAVA_API_QUERY, 3, RetrievalConstraint.none(), stageDeadlineNanos());

        assertEquals(2, capturedQueries.size());
        assertTrue(capturedQueries.stream()
                .allMatch(queryRequest -> queryRequest.getQuery().getNearest().hasSparse()));
        assertTrue(capturedQueries.stream().allMatch(queryRequest -> queryRequest
                .getFilter()
                .toString()
                .contains(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD)));
        assertTrue(capturedQueries.stream()
                .allMatch(queryRequest -> queryRequest.getFilter().toString().contains("List.html")));
        assertEquals(1, citationOutcome.size());
        verify(sparseEncoder).encode("List of");
        verify(qdrantClient, never()).scrollAsync(notNull(), notNull());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void explicitJavaMemberCitationSearchExcludesUnrelatedPromptTermsFromSparseEncoding() {
        String styledExampleQuery = "Show a Java 25 example with inline code String::formatted, a fenced code block, "
                + "and cite official Javadoc.";
        when(sparseEncoder.encode("String formatted"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L, 7L), List.of(3.0f, 1.0f)));
        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        List<Document> citationOutcome = buildSearchService()
                .searchDocumentationCitations(styledExampleQuery, 3, RetrievalConstraint.none(), stageDeadlineNanos());

        assertEquals(2, capturedQueries.size());
        assertTrue(capturedQueries.stream()
                .allMatch(queryRequest -> queryRequest.getFilter().toString().contains("String.html")));
        assertEquals(1, citationOutcome.size());
        verify(sparseEncoder).encode("String formatted");
        verify(qdrantClient, never()).scrollAsync(notNull(), notNull());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void exactCitationSearchFailsStrictlyWhenFilteredScrollFails() {
        doAnswer(invocation -> Futures.immediateFailedFuture(new RuntimeException("documentation unavailable")))
                .when(qdrantClient)
                .scrollAsync(notNull(), notNull());

        HybridSearchPartialFailureException citationSearchFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            citationSearchFailure = assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                    .searchDocumentationCitations(
                            EXACT_JAVA_API_QUERY, 3, RetrievalConstraint.none(), stageDeadlineNanos()));

            assertEquals(1, expectedLogEvents.events().size());
            assertEquals(
                    CITATION_COLLECTION_FAILURE_WARNING,
                    expectedLogEvents.events().getFirst().getFormattedMessage());
        }

        assertEquals(1, citationSearchFailure.collectionFailures().size());
        verifyNoInteractions(sparseEncoder);
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void exactCitationScrollTimeoutCancelsOriginalQdrantFuture() {
        appProperties.getQdrant().setQueryTimeout(SHARED_QUERY_TIMEOUT);
        SettableFuture<ScrollResponse> stalledScrollFuture = SettableFuture.create();
        when(qdrantClient.scrollAsync(notNull(), notNull())).thenReturn(stalledScrollFuture);

        assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                .searchDocumentationCitations(
                        EXACT_JAVA_API_QUERY, 3, RetrievalConstraint.none(), stageDeadlineNanos()));

        assertTrue(stalledScrollFuture.isCancelled());
    }

    @Test
    void citationSearchRemainsStrictWhenHybridPartialFailuresAreConfiguredAsNonFatal() {
        when(sparseEncoder.encode(CITATION_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));
        doAnswer(invocation -> Futures.immediateFailedFuture(new RuntimeException("documentation unavailable")))
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);

        HybridSearchPartialFailureException citationSearchFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            citationSearchFailure = assertThrows(
                    HybridSearchPartialFailureException.class,
                    () -> hybridSearchService.searchDocumentationCitations(
                            CITATION_QUERY, 3, officialDocumentationConstraint, stageDeadlineNanos()));

            assertEquals(2, expectedLogEvents.events().size());
            assertTrue(expectedLogEvents.events().stream()
                    .allMatch(logEvent -> logEvent.getFormattedMessage().startsWith("[QDRANT] Search failed")));
        }

        assertEquals(2, citationSearchFailure.collectionFailures().size());
        assertEquals(
                List.of(
                        appProperties.getQdrant().getCollections().getDocs(),
                        appProperties.getQdrant().getCollections().getPdfs()),
                citationSearchFailure.collectionFailures().stream()
                        .map(HybridSearchPartialFailureException.CollectionSearchFailure::collectionName)
                        .toList());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void sharesOneDispatchDeadlineAcrossMultipleStalledHybridQueries() {
        appProperties.getQdrant().setQueryTimeout(SHARED_QUERY_TIMEOUT);
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        List<SettableFuture<List<ScoredPoint>>> stalledQdrantQueryFutures = new ArrayList<>();
        doAnswer(invocation -> {
                    SettableFuture<List<ScoredPoint>> stalledQdrantQueryFuture = SettableFuture.create();
                    stalledQdrantQueryFutures.add(stalledQdrantQueryFuture);
                    return stalledQdrantQueryFuture;
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchService hybridSearchService = buildSearchService();
        HybridSearchPartialFailureException timedOutSearchFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            timedOutSearchFailure = assertTimeout(
                    SHARED_DEADLINE_ASSERTION_LIMIT,
                    () -> assertThrows(
                            HybridSearchPartialFailureException.class,
                            () -> hybridSearchService.search(
                                    HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos())));

            assertEquals(4, expectedLogEvents.events().size());
            assertTrue(expectedLogEvents.events().stream()
                    .allMatch(logEvent -> logEvent.getFormattedMessage().contains("Search timed out for collection=")));
        }
        assertEquals(4, timedOutSearchFailure.collectionFailures().size());
        assertTrue(timedOutSearchFailure.collectionFailures().stream()
                .allMatch(collectionFailure ->
                        collectionFailure.failureDetails().contains("Qdrant query exceeded timeout")));
        assertEquals(4, stalledQdrantQueryFutures.size());
        assertTrue(stalledQdrantQueryFutures.stream().allMatch(SettableFuture::isCancelled));
    }

    @Test
    void exhaustedDispatchBudgetFailsUndispatchedCollectionsImmediately() {
        appProperties.getQdrant().setQueryTimeout(EXHAUSTED_DISPATCH_QUERY_TIMEOUT);
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        AtomicInteger dispatchedQueryCount = new AtomicInteger();
        doAnswer(invocation -> {
                    dispatchedQueryCount.incrementAndGet();
                    Thread.sleep(EXHAUSTED_DISPATCH_BLOCKING_DURATION);
                    return Futures.immediateFuture(List.of());
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchPartialFailureException searchFailure =
                assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                        .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()));

        assertEquals(1, dispatchedQueryCount.get());
        assertFalse(searchFailure.collectionFailures().isEmpty());
        assertTrue(searchFailure.collectionFailures().stream().allMatch(collectionFailure -> collectionFailure
                .failureDetails()
                .contains("budget was exhausted before this collection was dispatched")));
    }

    @Test
    void interruptionAfterFanOutCancelsEveryPendingQuery() throws InterruptedException {
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        int collectionCount = appProperties.getQdrant().getCollections().all().size();
        CountDownLatch queryDispatches = new CountDownLatch(collectionCount);
        List<SettableFuture<List<ScoredPoint>>> stalledQdrantQueryFutures = new ArrayList<>();
        doAnswer(invocation -> {
                    SettableFuture<List<ScoredPoint>> stalledQdrantQueryFuture = SettableFuture.create();
                    stalledQdrantQueryFutures.add(stalledQdrantQueryFuture);
                    queryDispatches.countDown();
                    return stalledQdrantQueryFuture;
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
        HybridSearchService hybridSearchService = buildSearchService();
        AtomicReference<Thread> searchThread = new AtomicReference<>();
        AtomicBoolean interruptStatusPreserved = new AtomicBoolean();

        try (ExecutorService searchExecutor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<List<Document>> interruptedSearch = CompletableFuture.supplyAsync(
                    () -> {
                        searchThread.set(Thread.currentThread());
                        try {
                            return hybridSearchService.search(
                                    HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos());
                        } finally {
                            interruptStatusPreserved.set(Thread.currentThread().isInterrupted());
                        }
                    },
                    searchExecutor);
            assertTrue(queryDispatches.await(1, TimeUnit.SECONDS));

            searchThread.get().interrupt();

            ExecutionException searchExecutionFailure =
                    assertThrows(ExecutionException.class, () -> interruptedSearch.get(1, TimeUnit.SECONDS));
            HybridSearchPartialFailureException interruptionFailure =
                    assertInstanceOf(HybridSearchPartialFailureException.class, searchExecutionFailure.getCause());
            assertEquals("Qdrant retrieval was interrupted", interruptionFailure.getMessage());
            assertTrue(interruptStatusPreserved.get());
            assertEquals(collectionCount, stalledQdrantQueryFutures.size());
            assertTrue(stalledQdrantQueryFutures.stream().allMatch(SettableFuture::isCancelled));
        }
    }

    @Test
    void throwsWhenAnyCollectionFailsInStrictMode() {
        stubPartialFailureQueryResponses("collections health");

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint retrievalConstraint = RetrievalConstraint.none();

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            assertThrows(
                    HybridSearchPartialFailureException.class,
                    () -> hybridSearchService.search(
                            "collections health", 5, retrievalConstraint, stageDeadlineNanos()));
            assertCollectionFailureWarning(expectedLogEvents);
        }
    }

    @Test
    void unavailableGrpcFailureRemainsTypedAndRetryable() {
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        RuntimeException unavailableFailure = Status.UNAVAILABLE.asRuntimeException();
        AtomicInteger queryInvocationCount = new AtomicInteger();
        doAnswer(invocation -> queryInvocationCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(unavailableFailure)
                        : Futures.immediateFuture(List.of(scoredPoint())))
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchPartialFailureException searchFailure =
                assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                        .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()));

        assertTrue(searchFailure.isRetryable());
        assertSame(unavailableFailure, searchFailure.getCause());
        assertEquals(
                HybridSearchPartialFailureException.FailureDisposition.TRANSIENT,
                searchFailure.collectionFailures().getFirst().failureDisposition());
    }

    @Test
    void permissionDeniedGrpcFailureRemainsTypedAndPermanent() {
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        RuntimeException permissionDeniedFailure = Status.PERMISSION_DENIED.asRuntimeException();
        AtomicInteger queryInvocationCount = new AtomicInteger();
        doAnswer(invocation -> queryInvocationCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(permissionDeniedFailure)
                        : Futures.immediateFuture(List.of(scoredPoint())))
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchPartialFailureException searchFailure =
                assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                        .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()));

        assertFalse(searchFailure.isRetryable());
        assertSame(permissionDeniedFailure, searchFailure.getCause());
        assertEquals(
                HybridSearchPartialFailureException.FailureDisposition.PERMANENT,
                searchFailure.collectionFailures().getFirst().failureDisposition());
    }

    @Test
    void deadlineExceededGrpcFailureBecomesTimeoutCauseAndRetainsGrpcFailure() {
        when(embeddingClient.embed(eq(HYBRID_QUERY), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        RuntimeException deadlineExceededFailure = Status.DEADLINE_EXCEEDED.asRuntimeException();
        AtomicInteger queryInvocationCount = new AtomicInteger();
        doAnswer(invocation -> queryInvocationCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(deadlineExceededFailure)
                        : Futures.immediateFuture(List.of(scoredPoint())))
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchPartialFailureException searchFailure =
                assertThrows(HybridSearchPartialFailureException.class, () -> buildSearchService()
                        .search(HYBRID_QUERY, 5, RetrievalConstraint.none(), stageDeadlineNanos()));

        TimeoutException timeoutFailure = assertInstanceOf(TimeoutException.class, searchFailure.getCause());
        assertSame(deadlineExceededFailure, timeoutFailure.getCause());
        assertTrue(searchFailure.isRetryable());
        assertEquals(
                HybridSearchPartialFailureException.FailureDisposition.TRANSIENT,
                searchFailure.collectionFailures().getFirst().failureDisposition());
    }

    private static long stageDeadlineNanos() {
        return System.nanoTime() + STAGE_BUDGET_TEST_TIMEOUT.toNanos();
    }

    private HybridSearchService buildSearchService() {
        return new HybridSearchService(
                new QdrantQueryExecutor(qdrantClient, new QdrantSearchRequestFactory(), appProperties),
                new QueryEncodingServices(embeddingClient, sparseEncoder, new QdrantRetrievalConstraintBuilder()),
                new QdrantCollectionScopeResolver(appProperties, Optional.empty()));
    }

    private HybridSearchService buildSearchServiceWithGitHubDiscovery(
            QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery) {
        return new HybridSearchService(
                new QdrantQueryExecutor(qdrantClient, new QdrantSearchRequestFactory(), appProperties),
                new QueryEncodingServices(embeddingClient, sparseEncoder, new QdrantRetrievalConstraintBuilder()),
                new QdrantCollectionScopeResolver(appProperties, Optional.of(gitHubCollectionDiscovery)));
    }

    private void stubPartialFailureQueryResponses(String queryText) {
        when(embeddingClient.embed(eq(queryText), eq(LlmGatewayTier.LIVE), any(Duration.class)))
                .thenReturn(new float[] {0.5f, 0.1f, 0.4f});
        when(sparseEncoder.encode(queryText))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));

        AtomicInteger invocationCounter = new AtomicInteger();
        doAnswer(invocation -> {
                    int invocationIndex = invocationCounter.getAndIncrement();
                    if (invocationIndex == 0) {
                        return Futures.immediateFailedFuture(new RuntimeException("collection unavailable"));
                    }
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
    }

    private static void assertCollectionFailureWarning(ExpectedLogEvents expectedLogEvents) {
        assertEquals(1, expectedLogEvents.events().size());
        var collectionFailureWarning = expectedLogEvents.events().getFirst();
        assertEquals(Level.WARN, collectionFailureWarning.getLevel());
        assertEquals(COLLECTION_FAILURE_WARNING, collectionFailureWarning.getFormattedMessage());
        assertNull(collectionFailureWarning.getThrowableProxy());
    }

    /**
     * Pins the full retrieval payload selector so a metadata field dropped from the include
     * list fails loudly instead of passing a partial containment check.
     */
    private static void assertFullRetrievalPayloadSelector(List<String> includedFields) {
        List<String> expectedPayloadFields = new ArrayList<>(QdrantPayloadFieldSchema.ALL_METADATA_FIELDS);
        expectedPayloadFields.add(QdrantPayloadFieldSchema.DOC_CONTENT_FIELD);
        assertEquals(
                expectedPayloadFields.stream().sorted().toList(),
                includedFields.stream().sorted().toList());
    }

    private static ScoredPoint scoredPoint() {
        return ScoredPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(SCORED_POINT_UUID))
                .setScore(0.9f)
                .putPayload(
                        QdrantPayloadFieldSchema.DOC_CONTENT_FIELD,
                        io.qdrant.client.ValueFactory.value("Java stream examples"))
                .putPayload(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        io.qdrant.client.ValueFactory.value("https://docs.example.com/java/streams"))
                .putPayload(QdrantPayloadFieldSchema.TITLE_FIELD, io.qdrant.client.ValueFactory.value("Streams"))
                .build();
    }

    private static RetrievedPoint retrievedPoint() {
        return RetrievedPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(SCORED_POINT_UUID))
                .putPayload(
                        QdrantPayloadFieldSchema.DOC_CONTENT_FIELD,
                        io.qdrant.client.ValueFactory.value("List.of overload documentation"))
                .putPayload(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        io.qdrant.client.ValueFactory.value(
                                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html#of(E,E)"))
                .putPayload(QdrantPayloadFieldSchema.TITLE_FIELD, io.qdrant.client.ValueFactory.value("List.of"))
                .build();
    }
}
