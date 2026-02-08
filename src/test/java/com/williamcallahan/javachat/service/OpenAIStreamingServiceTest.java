package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.openai.core.http.Headers;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * Verifies primary-provider backoff and streaming failure classification.
 *
 * <p>Backoff tests target {@link OpenAiProviderRoutingService#shouldBackoffPrimary} directly
 * (package-private, same package). Streaming recovery tests exercise the public
 * {@link OpenAIStreamingService#isRecoverableStreamingFailure} API that delegates to
 * the routing service.</p>
 */
class OpenAIStreamingServiceTest {

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
    }

    private OpenAIStreamingService createStreamingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        return new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
    }

    @Test
    void shouldBackoffPrimaryTreatsSdkIoAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        assertTrue(routingService.shouldBackoffPrimary(new OpenAIIoException("io")));
    }

    @Test
    void shouldBackoffPrimaryTreats401AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        UnauthorizedException unauthorized =
                UnauthorizedException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(unauthorized));
    }

    @Test
    void shouldBackoffPrimaryTreats429AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        RateLimitException rateLimit =
                RateLimitException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(rateLimit));
    }

    @Test
    void shouldBackoffPrimaryTreats404AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();
        assertTrue(routingService.shouldBackoffPrimary(notFoundException));
    }

    @Test
    void shouldBackoffPrimaryDoesNotBackoffOnGenericRuntime() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        assertFalse(routingService.shouldBackoffPrimary(new IllegalArgumentException("no")));
    }

    @Test
    void recoverableStreamingFailureTreatsReactorOverflowTypeAsRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        assertTrue(streamingService.isRecoverableStreamingFailure(Exceptions.failWithOverflow()));
    }

    @Test
    void recoverableStreamingFailureTreatsValidationErrorsAsNonRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        assertFalse(
                streamingService.isRecoverableStreamingFailure(new IllegalArgumentException("bad request payload")));
    }

    @Test
    void recoverableStreamingFailureTreatsNotFoundAsRetryable() {
        OpenAIStreamingService streamingService = createStreamingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();
        assertTrue(streamingService.isRecoverableStreamingFailure(notFoundException));
    }
}
