package com.williamcallahan.javachat.service;

import static io.qdrant.client.PointIdFactory.id;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.util.concurrent.Futures;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantCollectionNames;
import com.williamcallahan.javachat.support.RetrySupport;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.FacetCounts;
import io.qdrant.client.grpc.Points.FacetHit;
import io.qdrant.client.grpc.Points.FacetValue;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpsertPoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Verifies each retried Qdrant write or count starts a fresh asynchronous operation.
 */
class HybridVectorServiceTest {

    private static final String COLLECTION_NAME = "retry-test-collection";
    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OBSOLETE_DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174001";
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
        when(embeddingClient.dimensions()).thenReturn(2);
        sparseVectorEncoder = mock(LexicalSparseVectorEncoder.class);
        AppProperties appProperties = new AppProperties();
        QdrantCollectionNames collectionNames = appProperties.getQdrant().getCollections();
        collectionNames.setDocs(COLLECTION_NAME);
        appProperties.getQdrant().setCollections(collectionNames);
        hybridVectorService =
                new HybridVectorService(qdrantClient, embeddingClient, sparseVectorEncoder, appProperties);
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
                .upsertAsync(any(UpsertPoints.class));

        Document document = new Document(DOCUMENT_ID, DOCUMENT_TEXT, Map.of());

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            assertDoesNotThrow(() -> hybridVectorService.upsertToCollection(COLLECTION_NAME, List.of(document)));
            assertRetryWarning(
                    expectedLogEvents,
                    "Qdrant hybrid upsert failed with transient error on attempt 1/3, retrying in 500ms");
        }

        ArgumentCaptor<UpsertPoints> upsertRequests = ArgumentCaptor.forClass(UpsertPoints.class);
        verify(qdrantClient, times(2)).upsertAsync(upsertRequests.capture());
        for (UpsertPoints upsertRequest : upsertRequests.getAllValues()) {
            assertEquals(COLLECTION_NAME, upsertRequest.getCollectionName());
            assertTrue(upsertRequest.getWait());
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
            assertRetryWarning(
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
            assertRetryWarning(
                    expectedLogEvents,
                    "Qdrant count by URL failed with transient error on attempt 1/3, retrying in 500ms");
        }

        assertEquals(RECOVERED_POINT_COUNT, pointCount);
    }

    @Test
    void exactUrlPointIdentityReadDistinguishesEqualSizedPointSets() {
        when(qdrantClient.scrollAsync(any(ScrollPoints.class)))
                .thenReturn(
                        Futures.immediateFuture(scrollResponse(DOCUMENT_ID)),
                        Futures.immediateFuture(scrollResponse(OBSOLETE_DOCUMENT_ID)));

        assertTrue(hybridVectorService.hasExactPointIdsForUrl(
                QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(DOCUMENT_ID)));
        assertFalse(hybridVectorService.hasExactPointIdsForUrl(COLLECTION_NAME, DOCUMENT_URL, List.of(DOCUMENT_ID)));
    }

    @Test
    void urlReplacementPreservesPriorPointsWhenReplacementUpsertFails() {
        when(qdrantClient.scrollAsync(any(ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(scrollResponse(DOCUMENT_ID, OBSOLETE_DOCUMENT_ID)));
        configureReplacementEmbedding();
        when(qdrantClient.upsertAsync(any(UpsertPoints.class)))
                .thenReturn(Futures.immediateFailedFuture(new IllegalArgumentException("upsert rejected")));
        Document replacementDocument = replacementDocument(DOCUMENT_ID, DOCUMENT_TEXT, DOCUMENT_URL);

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> hybridVectorService.replaceUrlDocuments(
                            QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(replacementDocument)));
            assertRetryWarning(
                    expectedLogEvents,
                    "Qdrant hybrid upsert failed with non-transient error on attempt 1/3, not retrying");
        }

        verify(qdrantClient, never()).deleteAsync(eq(COLLECTION_NAME), anyList());
        verify(qdrantClient, never()).countAsync(eq(COLLECTION_NAME), any(Filter.class), eq(true));
    }

    @Test
    void urlReplacementUpsertsBeforeDeletingOnlyObsoletePointsWhenPageShrinks() {
        PointId obsoletePointId = id(UUID.fromString(OBSOLETE_DOCUMENT_ID));
        when(qdrantClient.scrollAsync(any(ScrollPoints.class)))
                .thenReturn(
                        Futures.immediateFuture(scrollResponse(DOCUMENT_ID, OBSOLETE_DOCUMENT_ID)),
                        Futures.immediateFuture(scrollResponse(DOCUMENT_ID)));
        configureReplacementEmbedding();
        when(qdrantClient.upsertAsync(any(UpsertPoints.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        when(qdrantClient.deleteAsync(eq(COLLECTION_NAME), eq(List.of(obsoletePointId))))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        Document replacementDocument = replacementDocument(DOCUMENT_ID, DOCUMENT_TEXT, DOCUMENT_URL);

        hybridVectorService.replaceUrlDocuments(QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(replacementDocument));

        InOrder replacementOrder = inOrder(qdrantClient);
        replacementOrder.verify(qdrantClient).scrollAsync(any(ScrollPoints.class));
        replacementOrder.verify(qdrantClient).upsertAsync(any(UpsertPoints.class));
        replacementOrder.verify(qdrantClient).deleteAsync(COLLECTION_NAME, List.of(obsoletePointId));
        replacementOrder.verify(qdrantClient).scrollAsync(any(ScrollPoints.class));
        verify(qdrantClient, never()).countAsync(eq(COLLECTION_NAME), any(Filter.class), eq(true));
    }

    @Test
    void urlReplacementReportsObsoletePointDeletionFailureAfterSuccessfulUpsert() {
        PointId obsoletePointId = id(UUID.fromString(OBSOLETE_DOCUMENT_ID));
        when(qdrantClient.scrollAsync(any(ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(scrollResponse(DOCUMENT_ID, OBSOLETE_DOCUMENT_ID)));
        configureReplacementEmbedding();
        when(qdrantClient.upsertAsync(any(UpsertPoints.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        when(qdrantClient.deleteAsync(eq(COLLECTION_NAME), eq(List.of(obsoletePointId))))
                .thenReturn(Futures.immediateFailedFuture(new IllegalArgumentException("delete rejected")));
        Document replacementDocument = replacementDocument(DOCUMENT_ID, DOCUMENT_TEXT, DOCUMENT_URL);

        assertThrows(
                IllegalStateException.class,
                () -> hybridVectorService.replaceUrlDocuments(
                        QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(replacementDocument)));

        InOrder replacementOrder = inOrder(qdrantClient);
        replacementOrder.verify(qdrantClient).scrollAsync(any(ScrollPoints.class));
        replacementOrder.verify(qdrantClient).upsertAsync(any(UpsertPoints.class));
        replacementOrder.verify(qdrantClient).deleteAsync(COLLECTION_NAME, List.of(obsoletePointId));
        verify(qdrantClient, times(1)).scrollAsync(any(ScrollPoints.class));
    }

    @Test
    void urlReplacementRejectsEqualPointCountWithWrongIdentityAfterWaitedWrites() {
        when(qdrantClient.scrollAsync(any(ScrollPoints.class)))
                .thenReturn(
                        Futures.immediateFuture(scrollResponse(DOCUMENT_ID)),
                        Futures.immediateFuture(scrollResponse(OBSOLETE_DOCUMENT_ID)));
        configureReplacementEmbedding();
        when(qdrantClient.upsertAsync(any(UpsertPoints.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        Document replacementDocument = replacementDocument(DOCUMENT_ID, DOCUMENT_TEXT, DOCUMENT_URL);

        assertThrows(
                IllegalStateException.class,
                () -> hybridVectorService.replaceUrlDocuments(
                        QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(replacementDocument)));

        InOrder replacementOrder = inOrder(qdrantClient);
        replacementOrder.verify(qdrantClient).scrollAsync(any(ScrollPoints.class));
        replacementOrder.verify(qdrantClient).upsertAsync(any(UpsertPoints.class));
        replacementOrder.verify(qdrantClient).scrollAsync(any(ScrollPoints.class));
        verify(qdrantClient, never()).deleteAsync(eq(COLLECTION_NAME), anyList());
        verify(qdrantClient, never()).countAsync(eq(COLLECTION_NAME), any(Filter.class), eq(true));
    }

    @Test
    void urlReplacementRejectsMismatchedDocumentUrlBeforeQdrantMutation() {
        Document replacementDocument =
                replacementDocument(DOCUMENT_ID, DOCUMENT_TEXT, "https://docs.example.com/java/classes");

        assertThrows(
                IllegalArgumentException.class,
                () -> hybridVectorService.replaceUrlDocuments(
                        QdrantCollectionKind.DOCS, DOCUMENT_URL, List.of(replacementDocument)));

        verify(qdrantClient, never()).scrollAsync(any(ScrollPoints.class));
        verify(qdrantClient, never()).upsertAsync(any(UpsertPoints.class));
        verify(qdrantClient, never()).deleteAsync(eq(COLLECTION_NAME), anyList());
    }

    @Test
    void facetPayloadValuesReturnsExactCountsSortedByValue() {
        ArgumentCaptor<FacetCounts> facetRequest = ArgumentCaptor.forClass(FacetCounts.class);
        when(qdrantClient.facetAsync(facetRequest.capture()))
                .thenReturn(Futures.immediateFuture(
                        List.of(facetHit("oracle/javase/25/api", 9L), facetHit("jetbrains/idea/2025/09", 3L))));

        List<PayloadValueCount> payloadValueCounts =
                hybridVectorService.facetPayloadValues(COLLECTION_NAME, QdrantPayloadFieldSchema.DOC_SET_FIELD);

        assertEquals(COLLECTION_NAME, facetRequest.getValue().getCollectionName());
        assertEquals(
                QdrantPayloadFieldSchema.DOC_SET_FIELD, facetRequest.getValue().getKey());
        assertTrue(facetRequest.getValue().getExact());
        assertEquals(
                List.of(
                        new PayloadValueCount("jetbrains/idea/2025/09", 3L),
                        new PayloadValueCount("oracle/javase/25/api", 9L)),
                payloadValueCounts);
    }

    @Test
    void facetPayloadValuesRejectsNonStringValues() {
        when(qdrantClient.facetAsync(any(FacetCounts.class)))
                .thenReturn(Futures.immediateFuture(List.of(FacetHit.newBuilder()
                        .setValue(FacetValue.newBuilder().setIntegerValue(7L))
                        .setCount(2L)
                        .build())));

        assertThrows(
                IllegalStateException.class,
                () -> hybridVectorService.facetPayloadValues(COLLECTION_NAME, QdrantPayloadFieldSchema.DOC_SET_FIELD));
    }

    @Test
    void facetPayloadValuesFailsLoudlyAtTheValueLimit() {
        List<FacetHit> fullPage = new ArrayList<>();
        for (int valueIndex = 0; valueIndex < 256; valueIndex++) {
            fullPage.add(facetHit("docSet-" + valueIndex, 1L));
        }
        when(qdrantClient.facetAsync(any(FacetCounts.class))).thenReturn(Futures.immediateFuture(fullPage));

        IllegalStateException truncation = assertThrows(
                IllegalStateException.class,
                () -> hybridVectorService.facetPayloadValues(COLLECTION_NAME, QdrantPayloadFieldSchema.DOC_SET_FIELD));
        assertTrue(truncation.getMessage().contains("incomplete"));
    }

    private static FacetHit facetHit(String payloadValue, long pointCount) {
        return FacetHit.newBuilder()
                .setValue(FacetValue.newBuilder().setStringValue(payloadValue))
                .setCount(pointCount)
                .build();
    }

    private void configureReplacementEmbedding() {
        when(embeddingClient.embed(List.of(DOCUMENT_TEXT), LlmGatewayTier.BATCH))
                .thenReturn(List.of(new float[] {0.1f, 0.2f}));
        when(sparseVectorEncoder.encode(DOCUMENT_TEXT))
                .thenReturn(new LexicalSparseVectorEncoder.SparseVector(List.of(1L), List.of(1.0f)));
    }

    private static Document replacementDocument(String documentId, String documentText, String documentUrl) {
        return new Document(documentId, documentText, Map.of(QdrantPayloadFieldSchema.URL_FIELD, documentUrl));
    }

    private static ScrollResponse scrollResponse(String... documentIds) {
        ScrollResponse.Builder scrollResponseBuilder = ScrollResponse.newBuilder();
        for (String documentId : documentIds) {
            PointId pointId = id(UUID.fromString(documentId));
            scrollResponseBuilder.addResult(
                    RetrievedPoint.newBuilder().setId(pointId).build());
        }
        return scrollResponseBuilder.build();
    }

    private static void assertRetryWarning(ExpectedLogEvents expectedLogEvents, String expectedMessage) {
        assertEquals(1, expectedLogEvents.events().size());
        var retryWarning = expectedLogEvents.events().getFirst();
        assertEquals(Level.WARN, retryWarning.getLevel());
        assertEquals(expectedMessage, retryWarning.getFormattedMessage());
        assertNull(retryWarning.getThrowableProxy());
    }
}
