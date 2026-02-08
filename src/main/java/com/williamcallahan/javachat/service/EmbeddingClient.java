package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;

/**
 * Defines the application's embedding port independent from Spring AI abstractions.
 */
public interface EmbeddingClient {

    /**
     * Produces one dense embedding vector per input text, preserving input order.
     *
     * @param texts input texts
     * @return embedding vectors in the same order as {@code texts}
     */
    List<float[]> embed(List<String> texts);

    /**
     * Produces a dense embedding vector for a single text.
     *
     * @param text input text
     * @return embedding vector
     */
    default float[] embed(String text) {
        String safeText = Objects.requireNonNullElse(text, "");
        List<float[]> vectors = embed(List.of(safeText));
        if (vectors.isEmpty()) {
            throw new EmbeddingServiceUnavailableException("Embedding response was empty");
        }
        return vectors.get(0);
    }

    /**
     * Returns the configured vector dimensions for this embedding provider.
     *
     * @return embedding vector dimensions
     */
    int dimensions();
}
