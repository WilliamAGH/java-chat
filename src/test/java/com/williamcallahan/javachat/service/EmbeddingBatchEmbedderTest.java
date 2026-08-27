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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies bounded document embedding batches preserve the provider contract exactly. */
class EmbeddingBatchEmbedderTest {
    private static final int EMBEDDING_DIMENSIONS = 2;
    private static final int THREE_BATCH_DOCUMENT_COUNT = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * 2 + 1;
    private static final int REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT = 177_000;
    private static final int MAX_REPRESENTATIVE_JAVA_CORPUS_REQUEST_COUNT = 45_000;
    private static final int MINIMUM_PARALLEL_REQUEST_COUNT = 2;
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
                        1,
                        EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE,
                        EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE),
                embeddingClient.requestedTextBatches.stream()
                        .map(List::size)
                        .sorted()
                        .toList());
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
    void rejectsResponseCountMismatch() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE + 1;
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS,
                (requestIndex, textBatch) -> documentIndexFromText(textBatch.getFirst()) == 0
                        ? repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR)
                        : List.of());

        EmbeddingServiceUnavailableException mismatchFailure = assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount)));

        assertTrue(mismatchFailure.getMessage().contains("expected 1 but received 0"));
        assertTrue(mismatchFailure
                .getMessage()
                .contains("batch [" + EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE + ".."
                        + EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE + "]"));
        assertTrue(embeddingClient.requestedTextBatches.size() >= 1);
        assertTrue(embeddingClient.requestedTextBatches.size() <= 2);
    }

    @Test
    void propagatesBatchFailureWithoutFallback() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * 2 + 1;
        EmbeddingServiceUnavailableException providerFailure =
                new EmbeddingServiceUnavailableException("provider unavailable");
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    if (documentIndexFromText(textBatch.getFirst())
                            == EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE) {
                        throw providerFailure;
                    }
                    return repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR);
                });

        EmbeddingServiceUnavailableException batchFailure = assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount)));

        assertSame(providerFailure, batchFailure.getCause());
        int secondBatchStartIndex = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE;
        int secondBatchEndIndex = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * 2 - 1;
        assertTrue(batchFailure
                .getMessage()
                .contains("batch [" + secondBatchStartIndex + ".." + secondBatchEndIndex + "]"));
        assertTrue(
                batchFailure.getMessage().contains("firstUrl=https://docs.example.com/java/" + secondBatchStartIndex));
        assertTrue(batchFailure.getMessage().contains("lastUrl=https://docs.example.com/java/" + secondBatchEndIndex));
        assertTrue(embeddingClient.requestedTextBatches.size() >= 2);
        assertTrue(embeddingClient.requestedTextBatches.size() <= 3);
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
        int expectedFinalBatchSize = Math.floorMod(
                        REPRESENTATIVE_JAVA_CORPUS_CHUNK_COUNT - 1, EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE)
                + 1;
        assertTrue(embeddingClient.requestedTextBatches.stream()
                .anyMatch(requestedTextBatch -> requestedTextBatch.size() == expectedFinalBatchSize));
    }

    @Test
    void executesProviderRequestsConcurrently() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * MINIMUM_PARALLEL_REQUEST_COUNT;
        CountDownLatch concurrentRequestBarrier = new CountDownLatch(MINIMUM_PARALLEL_REQUEST_COUNT);
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    concurrentRequestBarrier.countDown();
                    try {
                        assertTrue(
                                concurrentRequestBarrier.await(5, TimeUnit.SECONDS),
                                "all requests in the bounded wave must execute concurrently");
                    } catch (InterruptedException interruptedBarrier) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("concurrency barrier was interrupted", interruptedBarrier);
                    }
                    return repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR);
                });

        EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount));

        assertEquals(MINIMUM_PARALLEL_REQUEST_COUNT, embeddingClient.maximumConcurrentRequests.get());
    }

    @Test
    void isolatesBlockingProviderRequestsOnVirtualThreads() {
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    return repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR);
                });

        EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(1));

        assertEquals(1, embeddingClient.requestedTextBatches.size());
    }

    @Test
    void interruptsBlockedSiblingWhenOneBatchFails() {
        CountDownLatch blockedRequestStarted = new CountDownLatch(1);
        AtomicBoolean blockedRequestInterrupted = new AtomicBoolean();
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    int firstDocumentIndex = documentIndexFromText(textBatch.getFirst());
                    if (firstDocumentIndex == 0) {
                        try {
                            assertTrue(blockedRequestStarted.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interruptedWait) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interruptedWait);
                        }
                        throw new EmbeddingServiceUnavailableException("first batch failed");
                    }
                    blockedRequestStarted.countDown();
                    try {
                        Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                        throw new AssertionError("blocked sibling was not interrupted");
                    } catch (InterruptedException expectedInterruption) {
                        blockedRequestInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new EmbeddingServiceUnavailableException(
                                "blocked sibling interrupted", expectedInterruption);
                    }
                });

        assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(
                        embeddingClient, sequentialDocuments(EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE * 2)));

        assertTrue(blockedRequestInterrupted.get());
    }

    @Test
    void boundsConcurrentProviderRequests() {
        int documentCount = EmbeddingBatchEmbedder.EMBEDDING_REQUEST_BATCH_SIZE
                * (EmbeddingBatchEmbedder.MAX_CONCURRENT_EMBEDDING_REQUESTS + 1);
        RecordingEmbeddingClient embeddingClient = new RecordingEmbeddingClient(
                EMBEDDING_DIMENSIONS,
                (requestIndex, textBatch) -> repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR));

        EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(documentCount));

        assertTrue(embeddingClient.maximumConcurrentRequests.get()
                <= EmbeddingBatchEmbedder.MAX_CONCURRENT_EMBEDDING_REQUESTS);
    }

    @Test
    void retriesTemporaryProviderFailuresBeforeMutationBoundary() {
        AtomicInteger providerAttempts = new AtomicInteger();
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    if (providerAttempts.incrementAndGet() < EmbeddingBatchEmbedder.MAX_EMBEDDING_ATTEMPTS) {
                        throw new EmbeddingServiceTemporarilyUnavailableException("temporary gateway failure");
                    }
                    return repeatedEmbeddings(textBatch.size(), EMBEDDING_VECTOR);
                });

        List<float[]> embeddingVectors = EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(1));

        assertEquals(1, embeddingVectors.size());
        assertEquals(EmbeddingBatchEmbedder.MAX_EMBEDDING_ATTEMPTS, providerAttempts.get());
    }

    @Test
    void doesNotRetryNonTransientProviderFailures() {
        AtomicInteger providerAttempts = new AtomicInteger();
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    providerAttempts.incrementAndGet();
                    throw new EmbeddingServiceUnavailableException("invalid provider response");
                });

        assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(1)));

        assertEquals(1, providerAttempts.get());
    }

    @Test
    void exhaustsBoundedRetriesForTemporaryProviderFailures() {
        AtomicInteger providerAttempts = new AtomicInteger();
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    providerAttempts.incrementAndGet();
                    throw new EmbeddingServiceTemporarilyUnavailableException("temporary gateway failure");
                });

        assertThrows(
                EmbeddingServiceUnavailableException.class,
                () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(1)));

        assertEquals(EmbeddingBatchEmbedder.MAX_EMBEDDING_ATTEMPTS, providerAttempts.get());
    }

    @Test
    void preservesCallerInterruptionInFailureCauseChain() {
        EmbeddingServiceTemporarilyUnavailableException providerFailure =
                new EmbeddingServiceTemporarilyUnavailableException("temporary gateway failure");
        RecordingEmbeddingClient embeddingClient =
                new RecordingEmbeddingClient(EMBEDDING_DIMENSIONS, (requestIndex, textBatch) -> {
                    throw providerFailure;
                });

        Thread.currentThread().interrupt();
        try {
            EmbeddingServiceUnavailableException batchFailure = assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> EmbeddingBatchEmbedder.embedDocuments(embeddingClient, sequentialDocuments(1)));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(batchFailure.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
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
        private final AtomicInteger requestSequence = new AtomicInteger();
        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maximumConcurrentRequests = new AtomicInteger();
        private final List<List<String>> requestedTextBatches = Collections.synchronizedList(new ArrayList<>());
        private final List<LlmGatewayTier> requestedTiers = Collections.synchronizedList(new ArrayList<>());

        private RecordingEmbeddingClient(
                int embeddingDimensions, BiFunction<Integer, List<String>, List<float[]>> batchEmbeddingFunction) {
            this.embeddingDimensions = embeddingDimensions;
            this.batchEmbeddingFunction = batchEmbeddingFunction;
        }

        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
            int requestIndex = requestSequence.getAndIncrement();
            requestedTextBatches.add(List.copyOf(texts));
            requestedTiers.add(requestTier);
            int activeRequestCount = activeRequests.incrementAndGet();
            maximumConcurrentRequests.accumulateAndGet(activeRequestCount, Math::max);
            try {
                return batchEmbeddingFunction.apply(requestIndex, texts);
            } finally {
                activeRequests.decrementAndGet();
            }
        }

        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier, java.time.Duration requestTimeout) {
            return embed(texts, requestTier);
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
