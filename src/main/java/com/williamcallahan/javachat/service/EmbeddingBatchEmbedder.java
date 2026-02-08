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

    private static final int EMBEDDING_REQUEST_BATCH_SIZE = 1;

    private EmbeddingBatchEmbedder() {}

    static List<float[]> embedDocuments(EmbeddingClient embeddingClient, List<Document> documents) {
        Objects.requireNonNull(embeddingClient, "embeddingClient");
        Objects.requireNonNull(documents, "documents");

        if (documents.isEmpty()) {
            return List.of();
        }

        List<float[]> allEmbeddings = new ArrayList<>(documents.size());
        for (int batchStartIndex = 0;
                batchStartIndex < documents.size();
                batchStartIndex += EMBEDDING_REQUEST_BATCH_SIZE) {
            int batchEndIndex = Math.min(batchStartIndex + EMBEDDING_REQUEST_BATCH_SIZE, documents.size());
            List<Document> documentBatch = documents.subList(batchStartIndex, batchEndIndex);
            List<float[]> batchEmbeddings =
                    embedSingleBatch(embeddingClient, documentBatch, batchStartIndex, batchEndIndex);
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
            EmbeddingClient embeddingClient, List<Document> documentBatch, int batchStartIndex, int batchEndIndex) {

        List<String> textBatch = documentBatch.stream()
                .map(document -> {
                    String text = document.getText();
                    return text == null ? "" : text;
                })
                .toList();

        List<float[]> batchEmbeddings;
        try {
            batchEmbeddings = embeddingClient.embed(textBatch);
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
        return batchEmbeddings;
    }

    private static String extractDocumentUrl(Document document, int fallbackIndex) {
        if (document == null) {
            return "unknown-url@" + fallbackIndex;
        }
        Object urlMetadata = document.getMetadata().get("url");
        if (urlMetadata instanceof String documentUrl && !documentUrl.isBlank()) {
            return documentUrl;
        }
        return "unknown-url@" + fallbackIndex;
    }
}
