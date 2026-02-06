package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks in-memory request and circuit-breaker state for one provider.
 */
final class ProviderCircuitState {
    private final int maxBackoffMultiplier;

    private volatile boolean circuitOpen = false;
    private volatile Instant nextRetryTime;
    private volatile int backoffMultiplier = 1;
    private final AtomicInteger requestsToday = new AtomicInteger(0);
    private volatile Instant dayReset = Instant.now().plus(Duration.ofDays(1));

    /**
     * Creates a provider state with bounded exponential backoff growth.
     */
    ProviderCircuitState(int maxBackoffMultiplier) {
        if (maxBackoffMultiplier <= 0) {
            throw new IllegalArgumentException("maxBackoffMultiplier must be positive");
        }
        this.maxBackoffMultiplier = maxBackoffMultiplier;
    }

    /**
     * Returns whether the provider is currently eligible for requests.
     */
    boolean isAvailable() {
        if (circuitOpen && Instant.now().isBefore(nextRetryTime)) {
            return false;
        }
        if (circuitOpen && Instant.now().isAfter(nextRetryTime)) {
            circuitOpen = false;
            backoffMultiplier = 1;
        }
        return true;
    }

    /**
     * Marks one successful request and closes the circuit if it was open.
     */
    void recordSuccess() {
        backoffMultiplier = 1;
        circuitOpen = false;
        requestsToday.incrementAndGet();
    }

    /**
     * Marks a rate limit and computes next retry using explicit delay or bounded exponential backoff.
     */
    void recordRateLimit(long retryAfterSeconds) {
        circuitOpen = true;
        if (retryAfterSeconds > 0) {
            nextRetryTime = Instant.now().plusSeconds(retryAfterSeconds);
            return;
        }
        backoffMultiplier = Math.min(backoffMultiplier * 2, maxBackoffMultiplier);
        nextRetryTime = Instant.now().plusSeconds(backoffMultiplier);
    }

    /**
     * Returns requests counted for the current rolling day and resets the counter when the window expires.
     */
    int requestsToday() {
        if (Instant.now().isAfter(dayReset)) {
            requestsToday.set(0);
            dayReset = Instant.now().plus(Duration.ofDays(1));
        }
        return requestsToday.get();
    }
}
