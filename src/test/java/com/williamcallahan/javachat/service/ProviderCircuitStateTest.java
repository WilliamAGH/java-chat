package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies provider circuit availability transitions.
 */
class ProviderCircuitStateTest {
    private static final int HIGH_REQUEST_COUNT = 1_000;
    private static final int CONCURRENT_TRANSITION_ITERATIONS = 10_000;
    private static final int CONCURRENT_TEST_TIMEOUT_SECONDS = 10;
    private static final long RATE_LIMIT_RETRY_SECONDS = 60;
    private static final Instant DAILY_WINDOW_START = Instant.parse("2026-07-16T00:00:00Z");

    @Test
    void freshCircuitIsAvailable() {
        ProviderCircuitState circuitState = new ProviderCircuitState(InstantSource.fixed(DAILY_WINDOW_START));

        assertTrue(circuitState.isAvailable());
    }

    @Test
    void rateLimitPublishesRetryDeadlineBeforeOpeningCircuit() {
        ProviderCircuitState circuitState = new ProviderCircuitState();

        circuitState.recordRateLimit(Instant.now().plusSeconds(RATE_LIMIT_RETRY_SECONDS));

        assertFalse(circuitState.isAvailable());
    }

    @Test
    void localAdmissionDoesNotInventAProviderQuota() {
        ProviderCircuitState circuitState = new ProviderCircuitState(InstantSource.fixed(DAILY_WINDOW_START));

        for (int requestIndex = 0; requestIndex < HIGH_REQUEST_COUNT; requestIndex++) {
            assertTrue(circuitState.tryReserveRequest());
        }
        assertTrue(circuitState.isAvailable());
    }

    @Test
    void activeRateLimitWindowNeverMovesBackward() {
        AtomicReference<Instant> currentTime = new AtomicReference<>(DAILY_WINDOW_START);
        ProviderCircuitState circuitState = new ProviderCircuitState(currentTime::get);

        circuitState.recordRateLimit(DAILY_WINDOW_START.plusSeconds(60));
        circuitState.recordRateLimit(DAILY_WINDOW_START.plusSeconds(1));
        currentTime.set(DAILY_WINDOW_START.plusSeconds(2));

        assertFalse(circuitState.isAvailable());

        currentTime.set(DAILY_WINDOW_START.plusSeconds(60));
        assertTrue(circuitState.isAvailable());

        currentTime.set(DAILY_WINDOW_START.plusSeconds(61));
        circuitState.recordRateLimit(DAILY_WINDOW_START.plusSeconds(62));
        circuitState.recordRateLimit(DAILY_WINDOW_START.plusSeconds(121));
        currentTime.set(DAILY_WINDOW_START.plusSeconds(63));

        assertFalse(circuitState.isAvailable());
    }

    @Test
    void olderInFlightSuccessDoesNotEraseNewerRateLimitWindow() {
        AtomicReference<Instant> currentTime = new AtomicReference<>(DAILY_WINDOW_START);
        ProviderCircuitState circuitState = new ProviderCircuitState(currentTime::get);

        circuitState.recordRateLimit(DAILY_WINDOW_START.plusSeconds(RATE_LIMIT_RETRY_SECONDS));
        circuitState.recordSuccess();

        assertFalse(circuitState.isAvailable());

        currentTime.set(DAILY_WINDOW_START.plusSeconds(RATE_LIMIT_RETRY_SECONDS));
        circuitState.recordSuccess();

        assertTrue(circuitState.isAvailable());
    }

    @Test
    void concurrentCircuitOpeningAndAvailabilityChecksDoNotExposePartialState()
            throws ExecutionException, InterruptedException, TimeoutException {
        ExecutorService transitionExecutor = Executors.newFixedThreadPool(2);
        AtomicReference<ProviderCircuitState> circuitStateReference = new AtomicReference<>();
        AtomicInteger completedAvailabilityChecks = new AtomicInteger();
        CyclicBarrier transitionBarrier = new CyclicBarrier(2);
        try {
            Future<?> rateLimitRecordingTask = transitionExecutor.submit(() -> {
                for (int iteration = 0; iteration < CONCURRENT_TRANSITION_ITERATIONS; iteration++) {
                    circuitStateReference.set(new ProviderCircuitState());
                    transitionBarrier.await();
                    circuitStateReference.get().recordRateLimit(Instant.now().plusSeconds(RATE_LIMIT_RETRY_SECONDS));
                }
                return null;
            });
            Future<?> availabilityCheckTask = transitionExecutor.submit(() -> {
                for (int iteration = 0; iteration < CONCURRENT_TRANSITION_ITERATIONS; iteration++) {
                    transitionBarrier.await();
                    circuitStateReference.get().isAvailable();
                    completedAvailabilityChecks.incrementAndGet();
                }
                return null;
            });

            rateLimitRecordingTask.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            availabilityCheckTask.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(CONCURRENT_TRANSITION_ITERATIONS, completedAvailabilityChecks.get());
            assertFalse(circuitStateReference.get().isAvailable());
        } finally {
            transitionExecutor.shutdownNow();
        }
    }
}
