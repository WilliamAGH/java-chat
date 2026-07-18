package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.util.concurrent.Futures;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.support.RetrySupport;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.UpdateResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Verifies each retried Qdrant write or count starts a fresh asynchronous operation.
 */
class HybridVectorServiceTest {

    private static final String COLLECTION_NAME = "retry-test-collection";
    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String DOCUMENT_TEXT = "Java records are immutable data carriers.";
    private static final String DOCUMENT_URL = "https://docs.example.com/java/records";
    private static final long RECOVERED_POINT_COUNT = 7L;
    private static final Logger RETRY_SUPPORT_LOGGER = (Logger) LoggerFactory.getLogger(RetrySupport.class);

    private QdrantClient qdrantClient;
    private EmbeddingClient embeddingClient;
    private LexicalSparseVectorEncoder sparseVectorEncoder;
    private HybridVectorService hybridVectorService;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        embeddingClient = mock(EmbeddingClient.class);
        sparseVectorEncoder = mock(LexicalSparseVectorEncoder.class);
        hybridVectorService =
                new HybridVectorService(qdrantClient, embeddingClient, sparseVectorEncoder, new AppProperties());
    }

    @Test
    void upsertStartsFreshOperationAfterTransientFailure() {
        when(embeddingClient.embed(List.of(DOCUMENT_TEXT), LlmGatewayTier.BATCH))
                .thenReturn(List.of(new float[] {0.1f, 0.2f}));
        when(sparseVectorEncoder.encode(DOCUMENT_TEXT))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
        AtomicInteger operationStartCount = new AtomicInteger();
        doAnswer(unusedInvocation -> operationStartCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException())
                        : Futures.immediateFuture(UpdateResult.getDefaultInstance()))
                .when(qdrantClient)
                .upsertAsync(eq(COLLECTION_NAME), anyList());

        Document document = new Document(DOCUMENT_ID, DOCUMENT_TEXT, Map.of());

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            assertDoesNotThrow(() -> hybridVectorService.upsertToCollection(COLLECTION_NAME, List.of(document)));
            assertTransientRetryWarning(
                    expectedLogEvents,
                    "Qdrant hybrid upsert failed with transient error on attempt 1/3, retrying in 500ms");
        }
    }

    @Test
    void deleteStartsFreshOperationAfterTransientFailure() {
        AtomicInteger operationStartCount = new AtomicInteger();
        doAnswer(unusedInvocation -> operationStartCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException())
                        : Futures.immediateFuture(UpdateResult.getDefaultInstance()))
                .when(qdrantClient)
                .deleteAsync(eq(COLLECTION_NAME), any(Filter.class));

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            assertDoesNotThrow(() -> hybridVectorService.deleteByUrl(COLLECTION_NAME, DOCUMENT_URL));
            assertTransientRetryWarning(
                    expectedLogEvents,
                    "Qdrant delete by URL failed with transient error on attempt 1/3, retrying in 500ms");
        }
    }

    @Test
    void countStartsFreshOperationAfterTransientFailure() {
        AtomicInteger operationStartCount = new AtomicInteger();
        doAnswer(unusedInvocation -> operationStartCount.getAndIncrement() == 0
                        ? Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException())
                        : Futures.immediateFuture(RECOVERED_POINT_COUNT))
                .when(qdrantClient)
                .countAsync(eq(COLLECTION_NAME), any(Filter.class), eq(true));

        long pointCount;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            pointCount = hybridVectorService.countPointsForUrl(COLLECTION_NAME, DOCUMENT_URL);
            assertTransientRetryWarning(
                    expectedLogEvents,
                    "Qdrant count by URL failed with transient error on attempt 1/3, retrying in 500ms");
        }

        assertEquals(RECOVERED_POINT_COUNT, pointCount);
    }

    private static void assertTransientRetryWarning(ExpectedLogEvents expectedLogEvents, String expectedMessage) {
        assertEquals(1, expectedLogEvents.events().size());
        var retryWarning = expectedLogEvents.events().getFirst();
        assertEquals(Level.WARN, retryWarning.getLevel());
        assertEquals(expectedMessage, retryWarning.getFormattedMessage());
        assertNull(retryWarning.getThrowableProxy());
    }
}
