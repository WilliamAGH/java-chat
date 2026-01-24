package com.williamcallahan.javachat.service;

/**
 * Signals that reranking failed and the caller should fall back to original ordering.
 */
public class RerankingFailureException extends RuntimeException {

    /**
     * Creates a reranking failure with a human-readable message.
     *
     * @param message explanation of the failure
     */
    public RerankingFailureException(String message) {
        super(message);
    }

    /**
     * Creates a reranking failure with a message and root cause.
     *
     * @param message explanation of the failure
     * @param cause underlying exception
     */
    public RerankingFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
