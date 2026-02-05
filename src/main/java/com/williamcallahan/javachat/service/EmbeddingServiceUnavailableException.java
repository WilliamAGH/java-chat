package com.williamcallahan.javachat.service;

/**
 * Signals that the configured embedding provider is unavailable or returned an invalid response.
 *
 * <p>This exception is thrown instead of returning synthetic vectors so callers can surface
 * provider failures and avoid caching corrupted embeddings.</p>
 */
public class EmbeddingServiceUnavailableException extends RuntimeException {

    /**
     * Creates an exception with a human-readable message.
     *
     * @param message explanation of the embedding failure
     */
    public EmbeddingServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and the original cause.
     *
     * @param message explanation of the embedding failure
     * @param cause underlying exception from the provider call
     */
    public EmbeddingServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
