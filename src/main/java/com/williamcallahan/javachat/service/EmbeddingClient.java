package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;

/**
 * Defines the application's embedding port independent from Spring AI abstractions.
 */
public interface EmbeddingClient {
    /** Minimal provider input used by keep-alive probes. */
    String EMBEDDING_WARM_UP_PROBE_TEXT = "embedding model warm-up probe";

    /**
     * Produces one dense embedding vector per input text, preserving input order.
     *
     * @param texts input texts
     * @param requestTier gateway capacity tier for this embedding request
     * @return embedding vectors in the same order as {@code texts}
     */
    List<float[]> embed(List<String> texts, LlmGatewayTier requestTier);

    /**
     * Returns the provider model identifier used for embedding requests.
     *
     * @return configured embedding model identifier
     */
    String modelName();

    /**
     * Produces a dense embedding vector for a single text.
     *
     * @param text input text
     * @param requestTier gateway capacity tier for this embedding request
     * @return embedding vector
     */
    default float[] embed(String text, LlmGatewayTier requestTier) {
        Objects.requireNonNull(requestTier, "requestTier");
        String safeText = Objects.requireNonNullElse(text, "");
        List<float[]> embeddingVectors = embed(List.of(safeText), requestTier);
        if (embeddingVectors.isEmpty()) {
            throw new EmbeddingServiceUnavailableException("Embedding response was empty");
        }
        return embeddingVectors.get(0);
    }

    /**
     * Returns the configured vector dimensions for this embedding provider.
     *
     * @return embedding vector dimensions
     */
    int dimensions();

    /**
     * Issues a minimal embedding request so the provider keeps its model resident.
     *
     * <p>Implementations must call their provider-specific request path directly instead
     * of delegating to {@link #embed(List, LlmGatewayTier)}. The RAG pipeline logging aspect advises
     * public {@code embed} executions, so routing scheduled probes around that method
     * keeps "STEP 1" pipeline logs scoped to real requests.</p>
     *
     * @throws EmbeddingServiceUnavailableException when the provider cannot serve the probe
     */
    void warmUp();
}
