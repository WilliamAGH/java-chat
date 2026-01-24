package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnexpectedStatusCodeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RateLimitManagerTest {

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesRetryAfterHeaderSeconds() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        MockEnvironment environment = new MockEnvironment();
        RateLimitManager manager = new RateLimitManager(rateLimitState, environment);

        Headers headers = Headers.builder().put("Retry-After", "12").build();
        RateLimitException exception = RateLimitException.builder().headers(headers).build();

        manager.recordRateLimitFromOpenAiServiceException(RateLimitManager.ApiProvider.OPENAI, exception);

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }

    @Test
    void recordRateLimitFromOpenAiServiceExceptionUsesResetWindowWhenRetryAfterMissing() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        MockEnvironment environment = new MockEnvironment();
        RateLimitManager manager = new RateLimitManager(rateLimitState, environment);

        Headers headers = Headers.builder().put("x-ratelimit-reset-requests", "2s").build();
        UnexpectedStatusCodeException exception = UnexpectedStatusCodeException.builder()
            .statusCode(429)
            .headers(headers)
            .build();

        manager.recordRateLimitFromOpenAiServiceException(RateLimitManager.ApiProvider.OPENAI, exception);

        verify(rateLimitState).recordRateLimit(eq("openai"), any(Instant.class), eq("1m"));
    }
}
