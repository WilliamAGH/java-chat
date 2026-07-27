package com.williamcallahan.javachat.service;

/**
 * Signals that strict LLM reranking failed and retrieval must terminate.
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
