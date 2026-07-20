package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies bounded document embedding batches preserve the provider contract exactly. */
class EmbeddingBatchEmbedderTest {
    private static final int EMBEDDING_DIMENSIONS = 2;
    private static final int THREE_BATCH_DOCUMENT_COUNT = 65;
    private static final int REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT = 177_000;
    private static final int MAX_REPRESENTATIVE_JAVA_CORPUS_REQUEST_COUNT = 6_000;
    private static final float[] EMBEDDING_VECTOR = new float[] {0.25f, 0.75f};

    @Test
    void usesBoundedMultiDocumentRequests() {
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS,
                (requestIndex, textBatch) -> repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR));

        List<float[]> embeddingVectors =
                EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(THREE_BATCH_DOCUMENT_COUNT));

        assertEquals(THREE_BATCH_DOCUMENT_COUNT, embeddingVectors.size());
        assertEquals(3, embeddingClient.requestedTextBatches.size());
        assertEquals(
                List.of(
                        EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE,
                        EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE,
                        1),
                embeddingClient.requestedTextBatches.stream().map(List::size).toList());
        assertTrue(embeddingClient.requestedTextBatches.stream()
                .allMatch(textBatch -> textBatch.size() <= EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE));
        assertTrue(
                embeddingClient.requestedTiers.stream().allMatch(requestTier -> requestTier == LlmGatewayTier.BATCH));
    }

    @Test
    void preservesProviderOutputOrderingAcrossBatchBoundaries() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE + 2;
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(1, (requestIndex, textBatch) -> textBatch.stream()
                        .map(documentText -> new float[] {documentIndexFromText(documentText)})
                        .toList());

        List<float[]> embeddingVectors =
                EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount));

        for (int documentIndex = 0; documentIndex < documentCount; documentIndex++) {
            assertArrayEquals(new float[] {documentIndex}, embeddingVectors.get(documentIndex));
        }
    }

    @Test
    void rejectsResponseCountMismatchWithoutContinuing() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE + 1;
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS,
                (requestIndex, textBatch) ->
                        requestIndex == 0 ? repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR) : List.of());

        EmbeddingServiceUnavailableException mismatchFailure = assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount)));

        assertTrue(mismatchFailure.getMessage().contains("expected 1 but received 0"));
        assertTrue(mismatchFailure.getMessage().contains("batch [32..32]"));
        assertEquals(2, embeddingClient.requestedTextBatches.size());
    }

    @Test
    void propagatesBatchFailureWithoutFallbackOrLaterRequests() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * 2 + 1;
        EmbeddingServiceUnavailableException providerFailure =
                new EmbeddingServiceUnavailableException("provider unavailable");
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    if (requestIndex == 1) {
                        throw providerFailure;
                    }
                    return repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR);
                });

        EmbeddingServiceUnavailableException batchFailure = assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount)));

        assertSame(providerFailure, batchFailure.getCause());
        assertTrue(batchFailure.getMessage().contains("batch [32..63]"));
        assertTrue(batchFailure.getMessage().contains("firstUrl=https://docs.example.com/java/32"));
        assertTrue(batchFailure.getMessage().contains("lastUrl=https://docs.example.com/java/63"));
        assertEquals(2, embeddingClient.requestedTextBatches.size());
    }

    @Test
    void rejectsEmbeddingDimensionMismatch() {
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> List.of(new float[] {0.5f}, EMBEDDING_VECTOR));

        EmbeddingServiceUnavailableException dimensionFailure = assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(2)));

        assertTrue(dimensionFailure.getMessage().contains("document index 0"));
        assertTrue(dimensionFailure.getMessage().contains("expected 2 but received 1"));
        assertEquals(1, embeddingClient.requestedTextBatches.size());
    }

    @Test
    void scalesRepresentativeJavaCorpusByBatchCount() {
        Document repeatedDocument = javaDocument(0);
        List<Document> representativeCorpus =
                Collections.nCopies(REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT, repeatedDocument);
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS,
                (requestIndex, textBatch) -> repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR));

        List<float[]> embeddingVectors = EmbeddingBatchEmbedder.embedDocuments(embeddingClient, representativeCorpus);

        int expectedRequestCount = Math.ceilDiv(
                REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT, EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE);
        assertEquals(REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT, embeddingVectors.size());
        assertEquals(expectedRequestCount, embeddingClient.requestedTextBatches.size());
        assertTrue(expectedRequestCount <= MAX_REPRESENTATIVE_JAVA_CORPUS_REQUEST_COUNT);
        assertEquals(
                REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT % EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE,
                embeddingClient.requestedTextBatches.getLast().size());
    }

    private static List<Document> sequentialDocuments(int documentCount) {
        return IntStream.range(0, documentCount)
                .mapToObj(EmbeddingBatchEmbedderTest::javaDocument)
                .toList();
    }

    private static Document javaDocument(int documentIndex) {
        return new Document(
                "java-document-" + documentIndex,
                "java-document-text-" + documentIndex,
                Map.of(QdrantPayloadFieldSchema.URL_FIELD, "https://docs.example.com/java/" + documentIndex));
    }

    private static int documentIndexFromText(String documentText) {
        return Integer.parseInt(documentText.substring(documentText.lastIndexOf('-') + 1));
    }

    private static List<float[]> repeatedEmbeddings(int embeddingCount, float[] embeddingVector) {
        return Collections.nCopies(embeddingCount, embeddingVector);
    }

    /** Records canonical embedding requests while supplying deterministic provider behavior. */
    private static final class RecordingEmbeddingClient implements EmbeddingClient {
        private final int embeddingDimensions;
        private final BiFunction<Integer, List<String>, List<float[]>> batchEmbeddingFunction;
        private final List<List<String>> requestedTextBatches = new ArrayList<>();
        private final List<LlmGatewayTier> requestedTiers = new ArrayList<>();

        private RecordingEmbeddingClient(
                int embeddingDimensions, BiFunction<Integer, List<String>, List<float[]>> batchEmbeddingFunction) {
            this.embeddingDimensions = embeddingDimensions;
            this.batchEmbeddingFunction = batchEmbeddingFunction;
        }

        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
            int requestIndex = requestedTextBatches.size();
            requestedTextBatches.add(List.copyOf(texts));
            requestedTiers.add(requestTier);
            return batchEmbeddingFunction.apply(requestIndex, texts);
        }

        @Override
        public String modelName() {
            return "test-embedding-model";
        }

        @Override
        public int dimensions() {
            return embeddingDimensions;
        }

        @Override
        public void warmUp() {
            throw new AssertionError("Batch embedding tests must not invoke warm-up");
        }
    }
}
