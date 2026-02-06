package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents an explicit rate-limit timing decision derived from provider headers.
 *
 * <p>A decision always contains both the reset instant and a retry-after duration in seconds.
 * This keeps persistence and in-memory backoff logic aligned and deterministic.</p>
 *
 * @param resetTime timestamp when requests may resume
 * @param retryAfterSeconds non-negative retry delay
 */
record RateLimitDecision(Instant resetTime, long retryAfterSeconds) {
    RateLimitDecision {
        Objects.requireNonNull(resetTime, "resetTime");
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be non-negative");
        }
    }

    /**
     * Creates a decision from an explicit Retry-After header value.
     */
    static RateLimitDecision fromRetryAfterSeconds(long retryAfterSeconds) {
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        Instant resetTime = Instant.now().plusSeconds(retryAfterSeconds);
        return new RateLimitDecision(resetTime, retryAfterSeconds);
    }

    /**
     * Creates a decision from an explicit reset timestamp.
     */
    static RateLimitDecision fromResetTime(Instant resetTime) {
        Objects.requireNonNull(resetTime, "resetTime");
        long retryAfterSeconds =
                Math.max(0, Duration.between(Instant.now(), resetTime).getSeconds());
        return new RateLimitDecision(resetTime, retryAfterSeconds);
    }
}
