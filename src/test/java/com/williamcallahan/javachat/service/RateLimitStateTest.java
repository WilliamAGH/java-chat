package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies rate-limit counters and backoff state transitions.
 */
class RateLimitStateTest {

    private static final String PROVIDER_NAME = "provider-under-test";

    private RateLimitState rateLimitState;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        rateLimitState = new RateLimitState(objectMapper);
    }

    @Test
    void isAvailable_doesNotResetConsecutiveFailuresWhenWindowExpires() throws Exception {
        Instant expiredResetTime = Instant.now().minus(Duration.ofSeconds(5));
        rateLimitState.recordRateLimit(PROVIDER_NAME, expiredResetTime, "1m");

        RateLimitState.ProviderState providerState = providerState(PROVIDER_NAME);
        assertEquals(1, providerState.getConsecutiveFailures());

        assertTrue(rateLimitState.isAvailable(PROVIDER_NAME));
        assertEquals(1, providerState.getConsecutiveFailures());
    }

    @Test
    void recordRateLimit_incrementsTotalFailuresCounter() throws Exception {
        rateLimitState.recordRateLimit(PROVIDER_NAME, null, "1m");

        RateLimitState.ProviderState providerState = providerState(PROVIDER_NAME);
        assertEquals(1, providerState.getTotalFailures());
    }

    private RateLimitState.ProviderState providerState(String providerName) throws Exception {
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
