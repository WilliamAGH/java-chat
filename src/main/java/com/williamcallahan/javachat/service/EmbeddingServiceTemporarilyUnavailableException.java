package com.williamcallahan.javachat.service;

/**
 * Signals that embedding work can be retried after a transient admission or provider failure.
 */
public final class EmbeddingServiceTemporarilyUnavailableException extends EmbeddingServiceUnavailableException {

    /**
     * Creates a retryable embedding failure with a human-readable explanation.
     *
     * @param message explanation of the transient failure
     */
    public EmbeddingServiceTemporarilyUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates a retryable embedding failure preserving its transport cause.
     *
     * @param message explanation of the transient failure
     * @param cause underlying provider or transport failure
     */
    public EmbeddingServiceTemporarilyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
