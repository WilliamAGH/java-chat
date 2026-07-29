package com.williamcallahan.javachat.service;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Tracks in-memory request and circuit-breaker state for one provider.
 */
final class ProviderCircuitState {
    private final InstantSource instantSource;

    private boolean circuitOpen = false;
    private Instant nextRetryTime = Instant.EPOCH;

    /**
     * Creates provider retry-window state using the system clock.
     */
    ProviderCircuitState() {
        this(InstantSource.system());
    }

    ProviderCircuitState(InstantSource instantSource) {
        this.instantSource = Objects.requireNonNull(instantSource, "instantSource");
    }

    /**
     * Returns whether one request can be admitted outside a provider-declared retry window.
     */
    synchronized boolean isAvailable() {
        return isRequestAdmissionAvailable(instantSource.instant());
    }

    /**
     * Atomically admits one provider request when its provider-declared retry window allows it.
     */
    synchronized boolean tryReserveRequest() {
        return isRequestAdmissionAvailable(instantSource.instant());
    }

    /**
     * Resets expired circuit state after a successful request.
     *
     * <p>A success observed during an active retry window belongs to a request admitted before
     * the rate limit was recorded, so it must not erase the newer provider deadline.</p>
     */
    synchronized void recordSuccess() {
        Instant successObservedAt = instantSource.instant();
        if (!circuitOpen || !successObservedAt.isBefore(nextRetryTime)) {
            circuitOpen = false;
        }
    }

    /**
     * Marks a rate limit using the exact provider-declared retry deadline.
     */
    synchronized void recordRateLimit(Instant providerRetryTime) {
        Instant requiredRetryTime = Objects.requireNonNull(providerRetryTime, "providerRetryTime");
        Instant currentTime = instantSource.instant();
        boolean activeWindow = circuitOpen && currentTime.isBefore(nextRetryTime);
        if (!activeWindow || requiredRetryTime.isAfter(nextRetryTime)) {
            nextRetryTime = requiredRetryTime;
        }
        circuitOpen = currentTime.isBefore(nextRetryTime);
    }

    private boolean isRequestAdmissionAvailable(Instant currentTime) {
        if (circuitOpen && !currentTime.isBefore(nextRetryTime)) {
            circuitOpen = false;
        }
        return !circuitOpen;
    }
}
