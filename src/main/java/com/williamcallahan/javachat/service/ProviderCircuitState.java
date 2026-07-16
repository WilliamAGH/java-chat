package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Tracks in-memory request and circuit-breaker state for one provider.
 */
final class ProviderCircuitState {
    private static final Duration DAILY_REQUEST_WINDOW = Duration.ofDays(1);

    private final int maxBackoffMultiplier;
    private final InstantSource instantSource;

    private boolean circuitOpen = false;
    private Instant nextRetryTime = Instant.EPOCH;
    private int backoffMultiplier = 1;
    private int requestsToday;
    private Instant dayReset;

    /**
     * Creates a provider state with bounded exponential backoff growth.
     */
    ProviderCircuitState(int maxBackoffMultiplier) {
        this(maxBackoffMultiplier, InstantSource.system());
    }

    ProviderCircuitState(int maxBackoffMultiplier, InstantSource instantSource) {
        if (maxBackoffMultiplier <= 0) {
            throw new IllegalArgumentException("maxBackoffMultiplier must be positive");
        }
        this.maxBackoffMultiplier = maxBackoffMultiplier;
        this.instantSource = Objects.requireNonNull(instantSource, "instantSource");
        this.dayReset = instantSource.instant().plus(DAILY_REQUEST_WINDOW);
    }

    /**
     * Returns whether one request could be admitted without consuming its daily reservation.
     */
    synchronized boolean isAvailable(int dailyLimit) {
        return isRequestAdmissionAvailable(dailyLimit, instantSource.instant());
    }

    /**
     * Atomically admits and counts one provider request when its circuit and daily window allow it.
     *
     * <p>Reservations are not released after dispatch failures, preventing retries from bypassing
     * the protective daily cap.</p>
     */
    synchronized boolean tryReserveRequest(int dailyLimit) {
        if (!isRequestAdmissionAvailable(dailyLimit, instantSource.instant())) {
            return false;
        }
        requestsToday++;
        return true;
    }

    /**
     * Closes the circuit after a successful request whose daily admission was already reserved.
     */
    synchronized void recordSuccess() {
        backoffMultiplier = 1;
        circuitOpen = false;
    }

    /**
     * Marks a rate limit and computes next retry using explicit delay or bounded exponential backoff.
     */
    synchronized void recordRateLimit(long retryAfterSeconds) {
        Instant retryTime;
        if (retryAfterSeconds > 0) {
            retryTime = instantSource.instant().plusSeconds(retryAfterSeconds);
        } else {
            backoffMultiplier = Math.min(backoffMultiplier * 2, maxBackoffMultiplier);
            retryTime = instantSource.instant().plusSeconds(backoffMultiplier);
        }
        nextRetryTime = retryTime;
        circuitOpen = true;
    }

    private boolean isRequestAdmissionAvailable(int dailyLimit, Instant currentTime) {
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException("dailyLimit must be positive");
        }
        if (circuitOpen && !currentTime.isBefore(nextRetryTime)) {
            circuitOpen = false;
            backoffMultiplier = 1;
        }
        if (!currentTime.isBefore(dayReset)) {
            requestsToday = 0;
            dayReset = currentTime.plus(DAILY_REQUEST_WINDOW);
        }
        return !circuitOpen && requestsToday < dailyLimit;
    }
}
