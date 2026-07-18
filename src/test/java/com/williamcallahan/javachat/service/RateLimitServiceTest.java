package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Verifies strict header-only rate-limit decision behavior.
 */
class RateLimitServiceTest {

    private static final Logger RATE_LIMIT_SERVICE_LOGGER = (Logger) LoggerFactory.getLogger(RateLimitService.class);

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesRetryAfterHeaderSeconds() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers = Headers.builder().put("Retry-After", "12").build();
        RateLimitException exception =
                RateLimitException.builder().headers(headers).build();

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RATE_LIMIT_SERVICE_LOGGER)) {
            rateLimitService.recordRateLimitFromOpenAiServiceException(RateLimitService.ApiProvider.OPENAI, exception);
            assertRateLimitWarning(expectedLogEvents, "[openai] Rate limited (retryAfterSeconds=12)");
        }

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesResetWindowWhenRetryAfterMissing() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers =
                Headers.builder().put("x-ratelimit-reset-requests", "2s").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
                .statusCode(429)
                .headers(headers)
                .build();

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RATE_LIMIT_SERVICE_LOGGER)) {
            rateLimitService.recordRateLimitFromOpenAiServiceException(RateLimitService.ApiProvider.OPENAI, exception);
            assertRateLimitWarning(expectedLogEvents, "[openai] Rate limited (retryAfterSeconds=");
        }

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionFailsWhenHeadersDoNotContainTiming() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers = Headers.builder().put("x-request-id", "abc").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
                .statusCode(429)
                .headers(headers)
                .build();

        assertThrows(
                RateLimitDecisionException.class,
                () -> rateLimitService.recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.OPENAI, exception));

        verifyNoInteractions(rateLimitState);
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionRejectsNonRateLimitStatus() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers = Headers.builder().put("Retry-After", "12").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
                .statusCode(HttpStatus.BAD_GATEWAY.value())
                .headers(headers)
                .build();

        assertThrows(
                RateLimitDecisionException.class,
                () -> rateLimitService.recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.OPENAI, exception));

        verifyNoInteractions(rateLimitState);
    }

    @Test
    void recordRateLimitFromExceptionUsesWebClientRetryAfterHeader() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "8");
        WebClientResponseException exception = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RATE_LIMIT_SERVICE_LOGGER)) {
            rateLimitService.recordRateLimitFromException(RateLimitService.ApiProvider.OPENAI, exception);
            assertRateLimitWarning(expectedLogEvents, "[openai] Rate limited (retryAfterSeconds=8)");
        }

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromExceptionFailsForNonWebClientErrors() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService rateLimitService = new RateLimitService(rateLimitState, new MockEnvironment());

        RuntimeException exception = new RuntimeException("network issue");

        assertThrows(
                RateLimitDecisionException.class,
                () -> rateLimitService.recordRateLimitFromException(RateLimitService.ApiProvider.OPENAI, exception));

        verifyNoInteractions(rateLimitState);
    }

    private static void assertRateLimitWarning(ExpectedLogEvents expectedLogEvents, String expectedMessagePrefix) {
        assertEquals(1, expectedLogEvents.events().size());
        var rateLimitWarning = expectedLogEvents.events().getFirst();
        assertEquals(Level.WARN, rateLimitWarning.getLevel());
        assertTrue(rateLimitWarning.getFormattedMessage().startsWith(expectedMessagePrefix));
        assertNull(rateLimitWarning.getThrowableProxy());
    }
}
