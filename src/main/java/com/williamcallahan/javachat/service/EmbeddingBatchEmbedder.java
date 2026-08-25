package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.springframework.ai.document.Document;

/**
 * Batches embedding requests while preserving input-document ordering guarantees.
 *
 * <p>Centralizing batch execution keeps embedding failures contextualized by
 * document range and ensures callers receive vectors aligned with document positions.</p>
 */
final class EmbeddingBatchEmbedder {

    static final int EMBEDDING_REQUEST_BATCH_SIZE = 8;
    static final int MAX_CONCURRENT_EMBEDDING_REQUESTS = 8;
    static final int MAX_EMBEDDING_ATTEMPTS = 3;
    private static final Duration EMBEDDING_RETRY_BASE_DELAY = Duration.ofSeconds(5);

    private EmbeddingBatchEmbedder() {}

    static List<float[]> embedDocuments(EmbeddingClient embeddingClient, List<Document> documents) {
        Objects.requireNonNull(embeddingClient, "embeddingClient");
        Objects.requireNonNull(documents, "documents");

        if (documents.isEmpty()) {
            return List.of();
        }

        int expectedEmbeddingDimensions = embeddingClient.dimensions();
        if (expectedEmbeddingDimensions <= 0) {
            throw new EmbeddingServiceUnavailableException(
                    "Embedding dimensions must be positive but were " + expectedEmbeddingDimensions);
        }

        int embeddingRequestCount = Math.ceilDiv(documents.size(), EMBEDDING_REQUEST_BATCH_SIZE);
        List<float[]> allEmbeddings = new ArrayList<>(documents.size());
        try (ExecutorService embeddingExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int requestWaveStartIndex = 0;
                    requestWaveStartIndex < embeddingRequestCount;
                    requestWaveStartIndex += MAX_CONCURRENT_EMBEDDING_REQUESTS) {
                int currentWaveStartIndex = requestWaveStartIndex;
                int requestWaveEndIndex =
                        Math.min(requestWaveStartIndex + MAX_CONCURRENT_EMBEDDING_REQUESTS, embeddingRequestCount);
                List<CompletableFuture<List<float[]>>> embeddingWave = IntStream.range(
                                currentWaveStartIndex, requestWaveEndIndex)
                        .mapToObj(requestIndex -> CompletableFuture.supplyAsync(
                                () -> embedRequest(
                                        embeddingClient, documents, requestIndex, expectedEmbeddingDimensions),
                                embeddingExecutor))
                        .toList();
                for (CompletableFuture<List<float[]>> embeddingRequest : embeddingWave) {
                    try {
                        allEmbeddings.addAll(embeddingRequest.get());
                    } catch (InterruptedException interruptedEmbeddingWave) {
                        embeddingWave.forEach(remainingRequest -> remainingRequest.cancel(true));
                        Thread.currentThread().interrupt();
                        throw new EmbeddingServiceUnavailableException(
                                "Embedding request wave was interrupted", interruptedEmbeddingWave);
                    } catch (ExecutionException embeddingCompletionFailure) {
                        embeddingWave.forEach(remainingRequest -> remainingRequest.cancel(true));
                        if (embeddingCompletionFailure.getCause()
                                instanceof EmbeddingServiceUnavailableException embeddingFailure) {
                            throw embeddingFailure;
                        }
                        throw new EmbeddingServiceUnavailableException(
                                "Embedding request wave failed", embeddingCompletionFailure.getCause());
                    }
                }
            }
        }
        return List.copyOf(allEmbeddings);
    }

    private static List<float[]> embedRequest(
            EmbeddingClient embeddingClient,
            List<Document> documents,
            int requestIndex,
            int expectedEmbeddingDimensions) {
        int batchStartIndex = requestIndex * EMBEDDING_REQUEST_BATCH_SIZE;
        int batchEndIndex = Math.min(batchStartIndex + EMBEDDING_REQUEST_BATCH_SIZE, documents.size());
        return embedSingleBatch(
                embeddingClient,
                documents.subList(batchStartIndex, batchEndIndex),
                batchStartIndex,
                batchEndIndex,
                expectedEmbeddingDimensions);
    }

    /**
     * Embeds a single batch of documents with contextual error wrapping.
     *
     * <p>Re-wraps embedding failures with batch range and URL context so upstream
     * callers can identify which documents caused the failure.</p>
     */
    private static List<float[]> embedSingleBatch(
            EmbeddingClient embeddingClient,
            List<Document> documentBatch,
            int batchStartIndex,
            int batchEndIndex,
            int expectedEmbeddingDimensions) {

        List<String> textBatch = documentBatch.stream()
                .map(document -> {
                    String text = document.getText();
                    return text == null ? "" : text;
                })
                .toList();

        List<float[]> batchEmbeddings;
        try {
            batchEmbeddings = embedWithRetry(embeddingClient, textBatch);
        } catch (EmbeddingServiceUnavailableException embeddingFailure) {
            String firstBatchUrl = extractDocumentUrl(documentBatch.getFirst(), batchStartIndex);
            String lastBatchUrl = extractDocumentUrl(documentBatch.getLast(), batchEndIndex - 1);
            throw new EmbeddingServiceUnavailableException(
                    "Embedding failed for batch ["
                            + batchStartIndex
                            + ".."
                            + (batchEndIndex - 1)
                            + "] (firstUrl="
                            + firstBatchUrl
                            + ", lastUrl="
                            + lastBatchUrl
                            + ")",
                    embeddingFailure);
        }

        if (batchEmbeddings == null) {
            throw new EmbeddingServiceUnavailableException(
                    "Embedding response was null for batch [" + batchStartIndex + ".." + (batchEndIndex - 1) + "]");
        }
        if (batchEmbeddings.size() != textBatch.size()) {
            throw new EmbeddingServiceUnavailableException("Embedding response count mismatch: expected "
                    + textBatch.size()
                    + " but received "
                    + batchEmbeddings.size()
                    + " for batch ["
                    + batchStartIndex
                    + ".."
                    + (batchEndIndex - 1)
                    + "]");
        }
        validateEmbeddingDimensions(batchEmbeddings, batchStartIndex, batchEndIndex, expectedEmbeddingDimensions);
        return batchEmbeddings;
    }

    private static List<float[]> embedWithRetry(EmbeddingClient embeddingClient, List<String> textBatch) {
        for (int embeddingAttempt = 1; embeddingAttempt <= MAX_EMBEDDING_ATTEMPTS; embeddingAttempt++) {
            try {
                return embeddingClient.embed(textBatch, LlmGatewayTier.BATCH);
            } catch (EmbeddingServiceTemporarilyUnavailableException temporaryFailure) {
                if (embeddingAttempt == MAX_EMBEDDING_ATTEMPTS) {
                    throw temporaryFailure;
                }
                awaitRetryDelay(embeddingAttempt, temporaryFailure);
            }
        }
        throw new IllegalStateException("Embedding retry loop exhausted without a terminal outcome");
    }

    private static void awaitRetryDelay(
            int completedAttemptCount, EmbeddingServiceTemporarilyUnavailableException temporaryFailure) {
        try {
            Thread.sleep(EMBEDDING_RETRY_BASE_DELAY.multipliedBy(completedAttemptCount));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            EmbeddingServiceTemporarilyUnavailableException interruptionFailure =
                    new EmbeddingServiceTemporarilyUnavailableException(
                            "Interrupted while waiting to retry embedding batch", interruptedException);
            interruptionFailure.addSuppressed(temporaryFailure);
            throw interruptionFailure;
        }
    }

    private static void validateEmbeddingDimensions(
            List<float[]> batchEmbeddings, int batchStartIndex, int batchEndIndex, int expectedEmbeddingDimensions) {
        for (int batchEmbeddingIndex = 0; batchEmbeddingIndex < batchEmbeddings.size(); batchEmbeddingIndex++) {
            float[] embeddingVector = batchEmbeddings.get(batchEmbeddingIndex);
            int documentIndex = batchStartIndex + batchEmbeddingIndex;
            if (embeddingVector == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Embedding response contained a null vector at document index " + documentIndex + " for batch ["
                                + batchStartIndex + ".." + (batchEndIndex - 1) + "]");
            }
            if (embeddingVector.length != expectedEmbeddingDimensions) {
                throw new EmbeddingServiceUnavailableException("Embedding dimension mismatch at document index "
                        + documentIndex + ": expected " + expectedEmbeddingDimensions + " but received "
                        + embeddingVector.length + " for batch [" + batchStartIndex + ".." + (batchEndIndex - 1)
                        + "]");
            }
        }
    }

    private static String extractDocumentUrl(Document document, int fallbackIndex) {
        if (document == null) {
            return "unknown-url@" + fallbackIndex;
        }
        Object urlMetadata = document.getMetadata().get(QdrantPayloadFieldSchema.URL_FIELD);
        if (urlMetadata instanceof String documentUrl && !documentUrl.isBlank()) {
            return documentUrl;
        }
        return "unknown-url@" + fallbackIndex;
    }
}
