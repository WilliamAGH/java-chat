package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies rate-limit counters and backoff state transitions.
 */
class RateLimitStateTest {

    private static final String PROVIDER_NAME = "provider-under-test";
    private static final Instant RATE_LIMIT_CHECK_TIME = Instant.parse("2026-07-16T00:00:00Z");
    private static final Instant EXPIRED_RATE_LIMIT_DEADLINE = RATE_LIMIT_CHECK_TIME.minusSeconds(1);
    private static final Instant REPLACEMENT_RATE_LIMIT_DEADLINE = RATE_LIMIT_CHECK_TIME.plus(Duration.ofMinutes(1));
    private static final int CONCURRENT_EXPIRY_CHECK_COUNT = 32;
    private static final int CONCURRENT_STATE_UPDATE_COUNT = 2;
    private static final int CONCURRENT_TEST_TIMEOUT_SECONDS = 5;

    private RateLimitState rateLimitState;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        rateLimitState = new RateLimitState(objectMapper);
    }

    @Test
    void isAvailable_doesNotResetConsecutiveFailuresWhenWindowExpires() throws ReflectiveOperationException {
        rateLimitState.recordRateLimit(PROVIDER_NAME, EXPIRED_RATE_LIMIT_DEADLINE, "1m");

        RateLimitState.ProviderState providerState = providerState(PROVIDER_NAME);
        assertEquals(1, providerState.getConsecutiveFailures());

        assertTrue(rateLimitState.isAvailable(PROVIDER_NAME));
        assertEquals(1, providerState.getConsecutiveFailures());
    }

    @Test
    void isAvailable_returnsAvailableForConcurrentExpiredWindowChecks()
            throws InterruptedException, ExecutionException, TimeoutException {
        rateLimitState.recordRateLimit(PROVIDER_NAME, Instant.EPOCH, "1m");
        ExecutorService expiryCheckExecutor = Executors.newFixedThreadPool(CONCURRENT_EXPIRY_CHECK_COUNT);
        CyclicBarrier expiryCheckBarrier = new CyclicBarrier(CONCURRENT_EXPIRY_CHECK_COUNT);
        List<Future<Boolean>> availabilityChecks = new ArrayList<>(CONCURRENT_EXPIRY_CHECK_COUNT);

        try {
            for (int checkIndex = 0; checkIndex < CONCURRENT_EXPIRY_CHECK_COUNT; checkIndex++) {
                availabilityChecks.add(expiryCheckExecutor.submit(() -> {
                    expiryCheckBarrier.await();
                    return rateLimitState.isAvailable(PROVIDER_NAME);
                }));
            }

            for (Future<Boolean> availabilityCheck : availabilityChecks) {
                assertTrue(availabilityCheck.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            expiryCheckExecutor.shutdownNow();
        }
    }

    @Test
    void providerState_clearsExpiredWindowOnceAcrossConcurrentEvaluations()
            throws InterruptedException, ExecutionException, TimeoutException {
        RateLimitState.ProviderState providerState = new RateLimitState.ProviderState();
        providerState.setRateLimitedUntil(EXPIRED_RATE_LIMIT_DEADLINE);
        ExecutorService expiryEvaluationExecutor = Executors.newFixedThreadPool(CONCURRENT_EXPIRY_CHECK_COUNT);
        CyclicBarrier expiryEvaluationBarrier = new CyclicBarrier(CONCURRENT_EXPIRY_CHECK_COUNT);
        List<Future<RateLimitState.ProviderState.RateLimitWindowEvaluation>> concurrentEvaluations =
                new ArrayList<>(CONCURRENT_EXPIRY_CHECK_COUNT);

        try {
            for (int checkIndex = 0; checkIndex < CONCURRENT_EXPIRY_CHECK_COUNT; checkIndex++) {
                concurrentEvaluations.add(expiryEvaluationExecutor.submit(() -> {
                    expiryEvaluationBarrier.await();
                    return providerState.evaluateRateLimitWindow(RATE_LIMIT_CHECK_TIME);
                }));
            }

            int expirationClearCount = 0;
            int availableEvaluationCount = 0;
            for (Future<RateLimitState.ProviderState.RateLimitWindowEvaluation> concurrentEvaluation :
                    concurrentEvaluations) {
                RateLimitState.ProviderState.RateLimitWindowEvaluation rateLimitWindowEvaluation =
                        concurrentEvaluation.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (rateLimitWindowEvaluation == RateLimitState.ProviderState.RateLimitWindowEvaluation.EXPIRED) {
                    expirationClearCount++;
                }
                if (rateLimitWindowEvaluation == RateLimitState.ProviderState.RateLimitWindowEvaluation.AVAILABLE) {
                    availableEvaluationCount++;
                }
            }

            assertEquals(1, expirationClearCount);
            assertEquals(CONCURRENT_EXPIRY_CHECK_COUNT - 1, availableEvaluationCount);
            assertNull(providerState.getRateLimitedUntil());
        } finally {
            expiryEvaluationExecutor.shutdownNow();
        }
    }

    @Test
    void providerState_preservesConcurrentReplacementDeadlineDuringExpiryEvaluation()
            throws InterruptedException, ExecutionException, TimeoutException {
        RateLimitState.ProviderState providerState = new RateLimitState.ProviderState();
        providerState.setRateLimitedUntil(EXPIRED_RATE_LIMIT_DEADLINE);
        ExecutorService stateUpdateExecutor = Executors.newFixedThreadPool(CONCURRENT_STATE_UPDATE_COUNT);
        CyclicBarrier stateUpdateBarrier = new CyclicBarrier(CONCURRENT_STATE_UPDATE_COUNT);

        try {
            Future<RateLimitState.ProviderState.RateLimitWindowEvaluation> expiryEvaluation =
                    stateUpdateExecutor.submit(() -> {
                        stateUpdateBarrier.await();
                        return providerState.evaluateRateLimitWindow(RATE_LIMIT_CHECK_TIME);
                    });
            Future<Void> replacementRecording = stateUpdateExecutor.submit(() -> {
                stateUpdateBarrier.await();
                providerState.recordRateLimit(REPLACEMENT_RATE_LIMIT_DEADLINE, RATE_LIMIT_CHECK_TIME);
                return null;
            });

            expiryEvaluation.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            replacementRecording.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(REPLACEMENT_RATE_LIMIT_DEADLINE, providerState.getRateLimitedUntil());
            assertEquals(1, providerState.getTotalFailures());
        } finally {
            stateUpdateExecutor.shutdownNow();
        }
    }

    @Test
    void recordRateLimit_incrementsTotalFailuresCounter() throws ReflectiveOperationException {
        rateLimitState.recordRateLimit(PROVIDER_NAME, null, "1m");

        RateLimitState.ProviderState providerState = providerState(PROVIDER_NAME);
        assertEquals(1, providerState.getTotalFailures());
    }

    @Test
    void recordRateLimit_preservesProviderResetTimeAcrossRepeatedFailures() throws ReflectiveOperationException {
        Instant providerResetTime = REPLACEMENT_RATE_LIMIT_DEADLINE;

        rateLimitState.recordRateLimit(PROVIDER_NAME, providerResetTime, "1m");
        rateLimitState.recordRateLimit(PROVIDER_NAME, providerResetTime, "1m");

        RateLimitState.ProviderState providerState = providerState(PROVIDER_NAME);
        assertEquals(providerResetTime, providerState.getRateLimitedUntil());
        assertEquals(2, providerState.getConsecutiveFailures());
    }

    private RateLimitState.ProviderState providerState(String providerName) throws ReflectiveOperationException {
        Field providerStatesField = RateLimitState.class.getDeclaredField("providerStates");
        providerStatesField.setAccessible(true);
        Object providerStatesRaw = providerStatesField.get(rateLimitState);
        if (!(providerStatesRaw instanceof Map<?, ?> providerStateMap)) {
            throw new IllegalStateException("providerStates field does not contain a map");
        }
        Object providerStateRaw = providerStateMap.get(providerName);
        if (!(providerStateRaw instanceof RateLimitState.ProviderState providerState)) {
            throw new IllegalStateException("No provider state found for provider: " + providerName);
        }
        return providerState;
    }
}
