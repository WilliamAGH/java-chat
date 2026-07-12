package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks in-memory request and circuit-breaker state for one provider.
 */
final class ProviderCircuitState {
    private final int maxBackoffMultiplier;

    private boolean circuitOpen = false;
    private Instant nextRetryTime = Instant.EPOCH;
    private int backoffMultiplier = 1;
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
    synchronized boolean isAvailable() {
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
    synchronized void recordSuccess() {
        backoffMultiplier = 1;
        circuitOpen = false;
        requestsToday.incrementAndGet();
    }

    /**
     * Marks a rate limit and computes next retry using explicit delay or bounded exponential backoff.
     */
    synchronized void recordRateLimit(long retryAfterSeconds) {
        Instant retryTime;
        if (retryAfterSeconds > 0) {
            retryTime = Instant.now().plusSeconds(retryAfterSeconds);
        } else {
            backoffMultiplier = Math.min(backoffMultiplier * 2, maxBackoffMultiplier);
            retryTime = Instant.now().plusSeconds(backoffMultiplier);
        }
        nextRetryTime = retryTime;
        circuitOpen = true;
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
