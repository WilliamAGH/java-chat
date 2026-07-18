package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_BACKOFF_MULTIPLIER = 32;
    private static final int DAILY_REQUEST_LIMIT = 8;
    private static final int CONCURRENT_RESERVATION_ATTEMPTS = 32;
    private static final int CONCURRENT_TRANSITION_ITERATIONS = 10_000;
    private static final int CONCURRENT_TEST_TIMEOUT_SECONDS = 10;
    private static final long RATE_LIMIT_RETRY_SECONDS = 60;
    private static final Instant DAILY_WINDOW_START = Instant.parse("2026-07-16T00:00:00Z");

    @Test
    void freshCircuitIsAvailable() {
        ProviderCircuitState circuitState =
                new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER, InstantSource.fixed(DAILY_WINDOW_START));

        assertTrue(circuitState.isAvailable(DAILY_REQUEST_LIMIT));
    }

    @Test
    void rateLimitPublishesRetryDeadlineBeforeOpeningCircuit() {
        ProviderCircuitState circuitState = new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER);

        circuitState.recordRateLimit(RATE_LIMIT_RETRY_SECONDS);

        assertFalse(circuitState.isAvailable(DAILY_REQUEST_LIMIT));
    }

    @Test
    void concurrentReservationsNeverExceedDailyRequestLimit()
            throws ExecutionException, InterruptedException, TimeoutException {
        ProviderCircuitState circuitState =
                new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER, InstantSource.fixed(DAILY_WINDOW_START));

        int successfulReservationCount = reserveConcurrently(circuitState);

        assertEquals(DAILY_REQUEST_LIMIT, successfulReservationCount);
        assertFalse(circuitState.tryReserveRequest(DAILY_REQUEST_LIMIT));
    }

    @Test
    void expiredDailyWindowResetsAtomicallyBeforeConcurrentReservations()
            throws ExecutionException, InterruptedException, TimeoutException {
        AtomicReference<Instant> currentTime = new AtomicReference<>(DAILY_WINDOW_START);
        ProviderCircuitState circuitState = new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER, currentTime::get);
        assertTrue(circuitState.tryReserveRequest(DAILY_REQUEST_LIMIT));
        currentTime.set(DAILY_WINDOW_START.plus(Duration.ofDays(2)));

        int successfulReservationCount = reserveConcurrently(circuitState);

        assertEquals(DAILY_REQUEST_LIMIT, successfulReservationCount);
        assertFalse(circuitState.tryReserveRequest(DAILY_REQUEST_LIMIT));
    }

    @Test
    void failedRequestAttemptKeepsItsDailyReservation() {
        ProviderCircuitState circuitState =
                new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER, InstantSource.fixed(DAILY_WINDOW_START));

        assertTrue(circuitState.tryReserveRequest(1));

        assertFalse(circuitState.tryReserveRequest(1));
    }

    @Test
    void successfulRequestDoesNotConsumeASecondDailyReservation() {
        ProviderCircuitState circuitState =
                new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER, InstantSource.fixed(DAILY_WINDOW_START));

        assertTrue(circuitState.tryReserveRequest(2));
        circuitState.recordSuccess();

        assertTrue(circuitState.tryReserveRequest(2));
        assertFalse(circuitState.tryReserveRequest(2));
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
                    circuitStateReference.set(new ProviderCircuitState(MAX_BACKOFF_MULTIPLIER));
                    transitionBarrier.await();
                    circuitStateReference.get().recordRateLimit(RATE_LIMIT_RETRY_SECONDS);
                }
                return null;
            });
            Future<?> availabilityCheckTask = transitionExecutor.submit(() -> {
                for (int iteration = 0; iteration < CONCURRENT_TRANSITION_ITERATIONS; iteration++) {
                    transitionBarrier.await();
                    circuitStateReference.get().isAvailable(DAILY_REQUEST_LIMIT);
                    completedAvailabilityChecks.incrementAndGet();
                }
                return null;
            });

            rateLimitRecordingTask.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            availabilityCheckTask.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(CONCURRENT_TRANSITION_ITERATIONS, completedAvailabilityChecks.get());
            assertFalse(circuitStateReference.get().isAvailable(DAILY_REQUEST_LIMIT));
        } finally {
            transitionExecutor.shutdownNow();
        }
    }

    private static int reserveConcurrently(ProviderCircuitState circuitState)
            throws ExecutionException, InterruptedException, TimeoutException {
        ExecutorService reservationExecutor = Executors.newFixedThreadPool(CONCURRENT_RESERVATION_ATTEMPTS);
        CyclicBarrier reservationBarrier = new CyclicBarrier(CONCURRENT_RESERVATION_ATTEMPTS);
        List<Future<Boolean>> reservationAttempts = new ArrayList<>(CONCURRENT_RESERVATION_ATTEMPTS);
        try {
            for (int attemptIndex = 0; attemptIndex < CONCURRENT_RESERVATION_ATTEMPTS; attemptIndex++) {
                reservationAttempts.add(reservationExecutor.submit(() -> {
                    reservationBarrier.await();
                    return circuitState.tryReserveRequest(DAILY_REQUEST_LIMIT);
                }));
            }

            int successfulReservationCount = 0;
            for (Future<Boolean> reservationAttempt : reservationAttempts) {
                if (reservationAttempt.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    successfulReservationCount++;
                }
            }
            return successfulReservationCount;
        } finally {
            reservationExecutor.shutdownNow();
        }
    }
}
