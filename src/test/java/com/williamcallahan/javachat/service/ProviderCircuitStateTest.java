package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies provider circuit availability transitions.
 */
class ProviderCircuitStateTest {
    private static final int CONCURRENT_TRANSITION_ITERATIONS = 10_000;
    private static final int CONCURRENT_TEST_TIMEOUT_SECONDS = 10;

    @Test
    void freshCircuitIsAvailable() {
        ProviderCircuitState circuitState = new ProviderCircuitState(32);

        assertTrue(circuitState.isAvailable());
    }

    @Test
    void rateLimitPublishesRetryDeadlineBeforeOpeningCircuit() {
        ProviderCircuitState circuitState = new ProviderCircuitState(32);

        circuitState.recordRateLimit(60);

        assertFalse(circuitState.isAvailable());
    }

    @Test
    void concurrentCircuitOpeningAndAvailabilityChecksDoNotExposePartialState() throws Exception {
        ExecutorService transitionExecutor = Executors.newFixedThreadPool(2);
        AtomicReference<ProviderCircuitState> circuitStateReference = new AtomicReference<>();
        AtomicInteger completedAvailabilityChecks = new AtomicInteger();
        CyclicBarrier transitionBarrier = new CyclicBarrier(2);
        try {
            Future<?> rateLimitRecordingTask = transitionExecutor.submit(() -> {
                for (int iteration = 0; iteration < CONCURRENT_TRANSITION_ITERATIONS; iteration++) {
                    circuitStateReference.set(new ProviderCircuitState(32));
                    transitionBarrier.await();
                    circuitStateReference.get().recordRateLimit(60);
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
