package com.williamcallahan.javachat.service;

/**
 * Signals that a rate-limit decision could not be derived from authoritative provider metadata.
 */
public final class RateLimitDecisionException extends RuntimeException {
    /**
     * Creates an exception with a descriptive message.
     */
    public RateLimitDecisionException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a descriptive message and root cause.
     */
    public RateLimitDecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
