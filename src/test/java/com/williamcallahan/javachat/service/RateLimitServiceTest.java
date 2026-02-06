package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnexpectedStatusCodeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Verifies strict header-only rate-limit decision behavior.
 */
class RateLimitServiceTest {

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesRetryAfterHeaderSeconds() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService manager = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers = Headers.builder().put("Retry-After", "12").build();
        RateLimitException exception =
                RateLimitException.builder().headers(headers).build();

        manager.recordRateLimitFromOpenAiServiceException(RateLimitService.ApiProvider.OPENAI, exception);

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesResetWindowWhenRetryAfterMissing() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService manager = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers =
                Headers.builder().put("x-ratelimit-reset-requests", "2s").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
                .statusCode(429)
                .headers(headers)
                .build();

        manager.recordRateLimitFromOpenAiServiceException(RateLimitService.ApiProvider.OPENAI, exception);

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionFailsWhenHeadersDoNotContainTiming() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService manager = new RateLimitService(rateLimitState, new MockEnvironment());

        Headers headers = Headers.builder().put("x-request-id", "abc").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
                .statusCode(429)
                .headers(headers)
                .build();

        assertThrows(
                RateLimitDecisionException.class,
                () -> manager.recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.OPENAI, exception));

        verifyNoInteractions(rateLimitState);
    }

    @Test
    void recordRateLimitFromExceptionUsesWebClientRetryAfterHeader() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService manager = new RateLimitService(rateLimitState, new MockEnvironment());

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "8");
        WebClientResponseException exception = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);

        manager.recordRateLimitFromException(RateLimitService.ApiProvider.OPENAI, exception);

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromExceptionFailsForNonWebClientErrors() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        RateLimitService manager = new RateLimitService(rateLimitState, new MockEnvironment());

        RuntimeException exception = new RuntimeException("network issue");

        assertThrows(
                RateLimitDecisionException.class,
                () -> manager.recordRateLimitFromException(RateLimitService.ApiProvider.OPENAI, exception));

        verifyNoInteractions(rateLimitState);
    }
}
