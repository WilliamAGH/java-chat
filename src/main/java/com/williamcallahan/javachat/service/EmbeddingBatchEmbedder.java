package com.williamcallahan.javachat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;

/**
 * Batches embedding requests while preserving input-document ordering guarantees.
 *
 * <p>Centralizing batch execution keeps embedding failures contextualized by
 * document range and ensures callers receive vectors aligned with document positions.</p>
 */
final class EmbeddingBatchEmbedder {

    static final int EMBEDDING_REQUEST_BATCH_SIZE = 32;

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

        List<float[]> allEmbeddings = new ArrayList<>(documents.size());
        for (int batchStartIndex = 0;
                batchStartIndex < documents.size();
                batchStartIndex += EMBEDDING_REQUEST_BATCH_SIZE) {
            int batchEndIndex = Math.min(batchStartIndex + EMBEDDING_REQUEST_BATCH_SIZE, documents.size());
            List<Document> documentBatch = documents.subList(batchStartIndex, batchEndIndex);
            List<float[]> batchEmbeddings = embedSingleBatch(
                    embeddingClient, documentBatch, batchStartIndex, batchEndIndex, expectedEmbeddingDimensions);
            allEmbeddings.addAll(batchEmbeddings);
        }
        return List.copyOf(allEmbeddings);
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
            batchEmbeddings = embeddingClient.embed(textBatch, LlmGatewayTier.BATCH);
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
