package com.williamcallahan.javachat.service;

import static io.qdrant.client.PointIdFactory.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.williamcallahan.javachat.config.AppProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies hybrid retrieval behavior for server-side filtering, fusion tuning, and partial failures.
 */
class HybridSearchServiceTest {

    @Test
    void appliesServerFilterToQueryAndPrefetchWithConfiguredRrfK() {
        QdrantClient qdrantClient = org.mockito.Mockito.mock(QdrantClient.class);
        EmbeddingClient embeddingClient = org.mockito.Mockito.mock(EmbeddingClient.class);
        LexicalSparseVectorEncoder sparseEncoder = org.mockito.Mockito.mock(LexicalSparseVectorEncoder.class);
        QdrantRetrievalConstraintBuilder constraintBuilder = new QdrantRetrievalConstraintBuilder();
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setRrfK(77);

        when(embeddingClient.embed("Java 25 streams")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(sparseEncoder.encode("Java 25 streams"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L, 3L), List.of(2.0f, 1.0f)));
        when(qdrantClient.queryAsync(any(QueryPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(scoredPoint())));

        HybridSearchService hybridSearchService =
                new HybridSearchService(qdrantClient, embeddingClient, sparseEncoder, constraintBuilder, appProperties);

        RetrievalConstraint retrievalConstraint = RetrievalConstraint.forDocVersion("25");
        hybridSearchService.searchOutcome("Java 25 streams", 5, retrievalConstraint);

        ArgumentCaptor<QueryPoints> requestCaptor = ArgumentCaptor.forClass(QueryPoints.class);
        verify(qdrantClient, times(4)).queryAsync(requestCaptor.capture());
        QueryPoints capturedQuery = requestCaptor.getAllValues().get(0);

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
        QdrantClient qdrantClient = org.mockito.Mockito.mock(QdrantClient.class);
        EmbeddingClient embeddingClient = org.mockito.Mockito.mock(EmbeddingClient.class);
        LexicalSparseVectorEncoder sparseEncoder = org.mockito.Mockito.mock(LexicalSparseVectorEncoder.class);
        QdrantRetrievalConstraintBuilder constraintBuilder = new QdrantRetrievalConstraintBuilder();
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setFailOnPartialSearchError(true);

        when(embeddingClient.embed("collections health")).thenReturn(new float[] {0.5f, 0.1f, 0.4f});
        when(sparseEncoder.encode("collections health"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));

        AtomicInteger invocationCounter = new AtomicInteger();
        when(qdrantClient.queryAsync(any(QueryPoints.class))).thenAnswer(unusedInvocation -> {
            int invocationIndex = invocationCounter.getAndIncrement();
            if (invocationIndex == 0) {
                return Futures.immediateFailedFuture(new RuntimeException("collection unavailable"));
            }
            return Futures.immediateFuture(List.of(scoredPoint()));
        });

        HybridSearchService hybridSearchService =
                new HybridSearchService(qdrantClient, embeddingClient, sparseEncoder, constraintBuilder, appProperties);

        assertThrows(
                HybridSearchPartialFailureException.class,
                () -> hybridSearchService.searchOutcome("collections health", 5, RetrievalConstraint.none()));
    }

    @Test
    void returnsNoticesWhenCollectionFailsInNonStrictMode() {
        QdrantClient qdrantClient = org.mockito.Mockito.mock(QdrantClient.class);
        EmbeddingClient embeddingClient = org.mockito.Mockito.mock(EmbeddingClient.class);
        LexicalSparseVectorEncoder sparseEncoder = org.mockito.Mockito.mock(LexicalSparseVectorEncoder.class);
        QdrantRetrievalConstraintBuilder constraintBuilder = new QdrantRetrievalConstraintBuilder();
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setFailOnPartialSearchError(false);

        when(embeddingClient.embed("collections health")).thenReturn(new float[] {0.5f, 0.1f, 0.4f});
        when(sparseEncoder.encode("collections health"))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(2L), List.of(1.0f)));

        AtomicInteger invocationCounter = new AtomicInteger();
        when(qdrantClient.queryAsync(any(QueryPoints.class))).thenAnswer(unusedInvocation -> {
            int invocationIndex = invocationCounter.getAndIncrement();
            if (invocationIndex == 0) {
                return Futures.immediateFailedFuture(new RuntimeException("collection unavailable"));
            }
            return Futures.immediateFuture(List.of(scoredPoint()));
        });

        HybridSearchService hybridSearchService =
                new HybridSearchService(qdrantClient, embeddingClient, sparseEncoder, constraintBuilder, appProperties);

        HybridSearchService.SearchOutcome searchOutcome =
                hybridSearchService.searchOutcome("collections health", 5, RetrievalConstraint.none());

        assertFalse(searchOutcome.notices().isEmpty());
    }

    private static ScoredPoint scoredPoint() {
        return ScoredPoint.newBuilder()
                .setId(id(UUID.randomUUID()))
                .setScore(0.9f)
                .putPayload("doc_content", io.qdrant.client.ValueFactory.value("Java stream examples"))
                .putPayload("url", io.qdrant.client.ValueFactory.value("https://docs.example.com/java/streams"))
                .putPayload("title", io.qdrant.client.ValueFactory.value("Streams"))
                .build();
    }
}
