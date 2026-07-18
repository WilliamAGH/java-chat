package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies hybrid and sparse citation retrieval behavior at the direct Qdrant request boundary.
 */
class HybridSearchServiceTest {

    private static final Logger HYBRID_SEARCH_LOGGER = (Logger) LoggerFactory.getLogger(HybridSearchService.class);
    private static final String COLLECTION_FAILURE_WARNING =
            "[QDRANT] Search failed for collection=java-chat-books (exceptionType=RuntimeException)";
    private static final String CITATION_COLLECTION_FAILURE_WARNING =
            "[QDRANT] Search failed for collection=java-docs (exceptionType=RuntimeException)";
    private static final String HYBRID_QUERY = "Java 25 streams";
    private static final String CITATION_QUERY = "Java records";
    private static final Duration DISPATCH_BUDGET_TEST_TIMEOUT = Duration.ofMillis(500);
    private static final long FIRST_DISPATCH_DELAY_MILLIS = 50;
    private static final Duration SHARED_QUERY_TIMEOUT = Duration.ofMillis(150);
    private static final Duration SHARED_DEADLINE_ASSERTION_LIMIT = Duration.ofMillis(450);
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
        appProperties.getQdrant().setQueryTimeout(DISPATCH_BUDGET_TEST_TIMEOUT);

        when(embeddingClient.embed(HYBRID_QUERY, LlmGatewayTier.LIVE)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode(HYBRID_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L, 3L), List.of(2.0f, 1.0f)));

        List<QueryPoints> capturedQueries = new ArrayList<>();
        List<Duration> capturedQueryTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    capturedQueryTimeouts.add(invocation.getArgument(1));
                    if (capturedQueryTimeouts.size() == 1) {
                        TimeUnit.MILLISECONDS.sleep(FIRST_DISPATCH_DELAY_MILLIS);
                    }
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint retrievalConstraint = RetrievalConstraint.forDocVersion("25");

        hybridSearchService.searchOutcome(HYBRID_QUERY, 5, retrievalConstraint);

        assertEquals(4, capturedQueries.size());
        assertTrue(capturedQueryTimeouts.stream()
                .allMatch(queryTimeout -> !queryTimeout.isZero()
                        && !queryTimeout.isNegative()
                        && queryTimeout.compareTo(DISPATCH_BUDGET_TEST_TIMEOUT) <= 0));
        assertTrue(capturedQueryTimeouts.getLast().compareTo(capturedQueryTimeouts.getFirst()) < 0);
        QueryPoints capturedQuery = capturedQueries.getFirst();
        assertEquals(77, capturedQuery.getQuery().getRrf().getK());
        assertTrue(capturedQuery.hasFilter());
        assertTrue(capturedQuery.getFilter().toString().contains("docVersion"));
        assertFalse(capturedQuery.getPrefetchList().isEmpty());
        for (PrefetchQuery prefetchQuery : capturedQuery.getPrefetchList()) {
            assertTrue(prefetchQuery.hasFilter());
        }
        verify(qdrantClient, never()).queryAsync(any(QueryPoints.class));
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
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java", "java/java25-complete"));

        HybridSearchService.SearchOutcome citationSearchOutcome =
                hybridSearchService.searchDocumentationCitationsOutcome(
                        CITATION_QUERY, 3, officialDocumentationConstraint);

        assertEquals(1, capturedQueries.size());
        assertEquals(1, capturedQueryTimeouts.size());
        Duration citationQueryTimeout = capturedQueryTimeouts.getFirst();
        assertFalse(citationQueryTimeout.isZero());
        assertFalse(citationQueryTimeout.isNegative());
        assertTrue(citationQueryTimeout.compareTo(appProperties.getQdrant().getQueryTimeout()) <= 0);
        QueryPoints citationQuery = capturedQueries.getFirst();
        assertEquals(appProperties.getQdrant().getCollections().getDocs(), citationQuery.getCollectionName());
        assertEquals("bm25", citationQuery.getUsing());
        assertEquals(3, citationQuery.getLimit());
        assertTrue(citationQuery.getWithPayload().getEnable());
        assertEquals(0, citationQuery.getPrefetchCount());
        assertTrue(citationQuery.getQuery().hasNearest());
        assertFalse(citationQuery.getQuery().hasRrf());
        assertTrue(citationQuery.getQuery().getNearest().hasSparse());
        assertEquals(
                List.of(2, 7), citationQuery.getQuery().getNearest().getSparse().getIndicesList());
        assertTrue(citationQuery.hasFilter());
        String officialFilter = citationQuery.getFilter().toString();
        assertTrue(officialFilter.contains("sourceKind"));
        assertTrue(officialFilter.contains("official"));
        assertTrue(officialFilter.contains("docSet"));
        assertTrue(officialFilter.contains("dev-java"));
        assertEquals(1, citationSearchOutcome.documents().size());
        assertEquals(
                appProperties.getQdrant().getCollections().getDocs(),
                citationSearchOutcome.documents().getFirst().getMetadata().get("collection"));
        verifyNoInteractions(embeddingClient);
        verify(gitHubCollectionDiscovery, never()).getDiscoveredCollections();
        verify(qdrantClient, never()).queryAsync(any(QueryPoints.class));
    }

    @Test
    void citationSearchRemainsStrictWhenHybridPartialFailuresAreConfiguredAsNonFatal() {
        appProperties.getQdrant().setFailOnPartialSearchError(false);
        when(sparseEncoder.encode(CITATION_QUERY))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));
        doAnswer(invocation -> Futures.immediateFailedFuture(new RuntimeException("documentation unavailable")))
                .when(qdrantClient)
                .queryAsync(notNull(), notNull());
        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java"));

        HybridSearchPartialFailureException citationSearchFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            citationSearchFailure = assertThrows(
                    HybridSearchPartialFailureException.class,
                    () -> hybridSearchService.searchDocumentationCitationsOutcome(
                            CITATION_QUERY, 3, officialDocumentationConstraint));

            assertEquals(1, expectedLogEvents.events().size());
            assertEquals(
                    CITATION_COLLECTION_FAILURE_WARNING,
                    expectedLogEvents.events().getFirst().getFormattedMessage());
        }

        assertEquals(1, citationSearchFailure.collectionFailures().size());
        assertEquals(
                appProperties.getQdrant().getCollections().getDocs(),
                citationSearchFailure.collectionFailures().getFirst().collectionName());
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void sharesOneDispatchDeadlineAcrossMultipleStalledHybridQueries() {
        appProperties.getQdrant().setFailOnPartialSearchError(false);
        appProperties.getQdrant().setQueryTimeout(SHARED_QUERY_TIMEOUT);
        when(embeddingClient.embed(HYBRID_QUERY, LlmGatewayTier.LIVE)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
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
        HybridSearchService.SearchOutcome timedOutSearchOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            timedOutSearchOutcome = assertTimeout(
                    SHARED_DEADLINE_ASSERTION_LIMIT,
                    () -> hybridSearchService.searchOutcome(HYBRID_QUERY, 5, RetrievalConstraint.none()));

            assertEquals(4, expectedLogEvents.events().size());
            assertTrue(expectedLogEvents.events().stream()
                    .allMatch(logEvent -> logEvent.getFormattedMessage().contains("Search timed out for collection=")));
        }
        assertEquals(4, timedOutSearchOutcome.notices().size());
        assertEquals(4, stalledQdrantQueryFutures.size());
        assertTrue(stalledQdrantQueryFutures.stream().allMatch(SettableFuture::isCancelled));
    }

    @Test
    void throwsWhenAnyCollectionFailsInStrictMode() {
        appProperties.getQdrant().setFailOnPartialSearchError(true);
        stubPartialFailureQueryResponses("collections health");

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint retrievalConstraint = RetrievalConstraint.none();

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            assertThrows(
                    HybridSearchPartialFailureException.class,
                    () -> hybridSearchService.searchOutcome("collections health", 5, retrievalConstraint));
            assertCollectionFailureWarning(expectedLogEvents);
        }
    }

    @Test
    void returnsNoticesWhenCollectionFailsInNonStrictMode() {
        appProperties.getQdrant().setFailOnPartialSearchError(false);
        stubPartialFailureQueryResponses("collections health");

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint retrievalConstraint = RetrievalConstraint.none();

        HybridSearchService.SearchOutcome searchOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            searchOutcome = hybridSearchService.searchOutcome("collections health", 5, retrievalConstraint);
            assertCollectionFailureWarning(expectedLogEvents);
        }

        assertFalse(searchOutcome.notices().isEmpty());
    }

    private HybridSearchService buildSearchService() {
        return new HybridSearchService(
                qdrantClient,
                new QueryEncodingServices(embeddingClient, sparseEncoder, new QdrantRetrievalConstraintBuilder()),
                appProperties,
                Optional.empty());
    }

    private HybridSearchService buildSearchServiceWithGitHubDiscovery(
            QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery) {
        return new HybridSearchService(
                qdrantClient,
                new QueryEncodingServices(embeddingClient, sparseEncoder, new QdrantRetrievalConstraintBuilder()),
                appProperties,
                Optional.of(gitHubCollectionDiscovery));
    }

    private void stubPartialFailureQueryResponses(String queryText) {
        when(embeddingClient.embed(queryText, LlmGatewayTier.LIVE)).thenReturn(new float[] {0.5f, 0.1f, 0.4f});
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

    private static ScoredPoint scoredPoint() {
        return ScoredPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(SCORED_POINT_UUID))
                .setScore(0.9f)
                .putPayload("doc_content", io.qdrant.client.ValueFactory.value("Java stream examples"))
                .putPayload("url", io.qdrant.client.ValueFactory.value("https://docs.example.com/java/streams"))
                .putPayload("title", io.qdrant.client.ValueFactory.value("Streams"))
                .build();
    }
}
