package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.io.InterruptedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * Verifies provider ordering, primary backoff, and failure classification policy.
 *
 * <p>This suite targets routing directly so streaming transport tests remain focused on stream behavior.</p>
 */
class OpenAiProviderRoutingServiceTest {
    private static final long PRIMARY_BACKOFF_SECONDS = 600L;

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
    }

    @Test
    void shouldBackoffPrimaryTreatsSdkIoAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.shouldBackoffPrimary(new OpenAIIoException("io")));
    }

    @Test
    void shouldBackoffPrimaryIgnoresCallerCancellationWrappedBySdkIo() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        InterruptedIOException interruptedRequest = new InterruptedIOException("request interrupted by caller timeout");
        OpenAIIoException cancelledCompletion = new OpenAIIoException("Request failed", interruptedRequest);

        assertFalse(routingService.shouldBackoffPrimary(cancelledCompletion));
    }

    @Test
    void callerCancellationKeepsConfiguredPrimaryProviderEligible() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        InterruptedIOException interruptedRequest = new InterruptedIOException("request interrupted by caller timeout");
        OpenAIIoException cancelledCompletion = new OpenAIIoException("Request failed", interruptedRequest);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, cancelledCompletion);

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(githubModelsClient, openAiClient);
        assertEquals(
                List.of(RateLimitService.ApiProvider.OPENAI, RateLimitService.ApiProvider.GITHUB_MODELS),
                availableProviders.stream()
                        .map(OpenAiProviderCandidate::provider)
                        .toList());
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
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.isRecoverableStreamingFailure(Exceptions.failWithOverflow()));
    }

    @Test
    void recoverableStreamingFailureTreatsValidationErrorsAsNonRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.isRecoverableStreamingFailure(new IllegalArgumentException("bad request payload")));
    }

    @Test
    void recoverableStreamingFailureTreatsNotFoundAsNonRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();

        assertFalse(routingService.isRecoverableStreamingFailure(notFoundException));
    }

    @Test
    void recoverableStreamingFailureRejectsAuthenticationAuthorizationAndRateLimitFailures() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();

        assertFalse(routingService.isRecoverableStreamingFailure(
                UnauthorizedException.builder().headers(headers).build()));
        assertFalse(routingService.isRecoverableStreamingFailure(
                PermissionDeniedException.builder().headers(headers).build()));
        assertFalse(routingService.isRecoverableStreamingFailure(
                RateLimitException.builder().headers(headers).build()));
    }

    @Test
    void primaryBackoffDoesNotHideOnlyConfiguredProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(null, openAiClient);

        assertEquals(1, availableProviders.size());
        assertEquals(
                RateLimitService.ApiProvider.OPENAI, availableProviders.get(0).provider());
    }

    @Test
    void primaryBackoffUsesConfiguredAlternateProviderWhenAvailable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        List<OpenAiProviderCandidate> availableProviders =
                routingService.selectAvailableProviderCandidates(githubModelsClient, openAiClient);

        assertEquals(1, availableProviders.size());
        assertEquals(
                RateLimitService.ApiProvider.GITHUB_MODELS,
                availableProviders.get(0).provider());
    }
}
