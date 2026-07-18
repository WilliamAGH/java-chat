package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.util.concurrent.Futures;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies hybrid retrieval behavior for server-side filtering, fusion tuning, and partial failures.
 */
class HybridSearchServiceTest {

    private static final Logger HYBRID_SEARCH_LOGGER = (Logger) LoggerFactory.getLogger(HybridSearchService.class);
    private static final String COLLECTION_FAILURE_WARNING =
            "[QDRANT] Search failed for collection=java-chat-books (exceptionType=RuntimeException)";

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
    void appliesServerFilterToQueryAndPrefetchWithConfiguredRrfK() {
        appProperties.getQdrant().setRrfK(77);

        when(embeddingClient.embed("Java 25 streams", LlmGatewayTier.LIVE)).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode("Java 25 streams"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L, 3L), List.of(2.0f, 1.0f)));

        List<QueryPoints> capturedQueries = new ArrayList<>();
        doAnswer(invocation -> {
                    capturedQueries.add(invocation.getArgument(0));
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull());

        HybridSearchService hybridSearchService = buildSearchService();

        RetrievalConstraint retrievalConstraint = RetrievalConstraint.forDocVersion("25");
        hybridSearchService.searchOutcome("Java 25 streams", 5, retrievalConstraint);

        assertEquals(4, capturedQueries.size());
        QueryPoints capturedQuery = capturedQueries.get(0);

        assertEquals(77, capturedQuery.getQuery().getRrf().getK());
        assertTrue(capturedQuery.hasFilter());
        assertTrue(capturedQuery.getFilter().toString().contains("docVersion"));
        assertFalse(capturedQuery.getPrefetchList().isEmpty());
        for (PrefetchQuery prefetchQuery : capturedQuery.getPrefetchList()) {
            assertTrue(prefetchQuery.hasFilter());
        }
    }

    @Test
    void throwsWhenAnyCollectionFailsInStrictMode() {
        appProperties.getQdrant().setFailOnPartialSearchError(true);
        stubPartialFailureQueryResponses("collections health");

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint constraint = RetrievalConstraint.none();

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            assertThrows(
                    HybridSearchPartialFailureException.class,
                    () -> hybridSearchService.searchOutcome("collections health", 5, constraint));
            assertCollectionFailureWarning(expectedLogEvents);
        }
    }

    @Test
    void returnsNoticesWhenCollectionFailsInNonStrictMode() {
        appProperties.getQdrant().setFailOnPartialSearchError(false);
        stubPartialFailureQueryResponses("collections health");

        HybridSearchService hybridSearchService = buildSearchService();
        RetrievalConstraint constraint = RetrievalConstraint.none();

        HybridSearchService.SearchOutcome searchOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(HYBRID_SEARCH_LOGGER)) {
            searchOutcome = hybridSearchService.searchOutcome("collections health", 5, constraint);
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

    private void stubPartialFailureQueryResponses(String queryText) {
        when(embeddingClient.embed(queryText, LlmGatewayTier.LIVE)).thenReturn(new float[] {0.5f, 0.1f, 0.4f});
        when(sparseEncoder.encode(queryText))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));

        AtomicInteger invocationCounter = new AtomicInteger();
        doAnswer(unusedInvocation -> {
                    int invocationIndex = invocationCounter.getAndIncrement();
                    if (invocationIndex == 0) {
                        return Futures.immediateFailedFuture(new RuntimeException("collection unavailable"));
                    }
                    return Futures.immediateFuture(List.of(scoredPoint()));
                })
                .when(qdrantClient)
                .queryAsync(notNull());
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
                .setId(io.qdrant.client.PointIdFactory.id(Objects.requireNonNull(UUID.randomUUID())))
                .setScore(0.9f)
                .putPayload("doc_content", io.qdrant.client.ValueFactory.value("Java stream examples"))
                .putPayload("url", io.qdrant.client.ValueFactory.value("https://docs.example.com/java/streams"))
                .putPayload("title", io.qdrant.client.ValueFactory.value("Streams"))
                .build();
    }
}
