package com.williamcallahan.javachat.service;

import java.time.Duration;

/**
 * Signals that embedding work can be retried after a transient admission or provider failure.
 *
 * <p>Non-final so the embedding client can distinguish a locally recorded provider cooldown
 * (no provider contact made) from a genuine transient provider failure through subtyping.</p>
 */
public class EmbeddingServiceTemporarilyUnavailableException extends EmbeddingServiceUnavailableException {
    private final long retryNotBeforeNanos;

    /**
     * Creates a retryable embedding failure with a human-readable explanation.
     *
     * @param message explanation of the transient failure
     */
    public EmbeddingServiceTemporarilyUnavailableException(String message) {
        this(message, null, Duration.ZERO);
    }

    /**
     * Creates a retryable embedding failure preserving its transport cause.
     *
     * @param message explanation of the transient failure
     * @param cause underlying provider or transport failure
     */
    public EmbeddingServiceTemporarilyUnavailableException(String message, Throwable cause) {
        this(message, cause, Duration.ZERO);
    }

    /** Preserves authoritative provider retry timing across the batch boundary. */
    public EmbeddingServiceTemporarilyUnavailableException(String message, Throwable cause, Duration retryDelay) {
        super(message, cause);
        retryNotBeforeNanos = System.nanoTime() + retryDelay.toNanos();
    }

    /** Returns the remaining authoritative cooldown without parsing diagnostic text. */
    public Duration retryDelay() {
        return Duration.ofNanos(Math.max(0, retryNotBeforeNanos - System.nanoTime()));
    }

    /** Distinguishes provider failures from local admission rejection for retry budgeting. */
    public boolean providerContacted() {
        return true;
    }
}
