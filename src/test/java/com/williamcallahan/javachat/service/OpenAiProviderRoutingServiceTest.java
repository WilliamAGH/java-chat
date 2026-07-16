package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

/**
 * Verifies configured-provider validation, backoff, and failure classification policy.
 *
 * <p>This suite targets routing directly so streaming transport tests remain focused on stream behavior.</p>
 */
class OpenAiProviderRoutingServiceTest {
    private static final long PRIMARY_BACKOFF_SECONDS = 600L;
    private static final String OPENAI_REQUEST_FAILED_MESSAGE = "Request failed";
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";
    private static final String CALLER_INTERRUPTION_MESSAGE = "request interrupted by caller timeout";

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
    }

    @Test
    void shouldBackoffConfiguredProviderTreatsSdkIoAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.shouldBackoffConfiguredProvider(new OpenAIIoException("io")));
    }

    @Test
    void shouldBackoffConfiguredProviderIgnoresWrappedCallerInterruption() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.shouldBackoffConfiguredProvider(wrappedCallerInterruption()));
    }

    @Test
    void shouldBackoffConfiguredProviderTreatsWrappedOkHttpCallTimeoutAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.shouldBackoffConfiguredProvider(wrappedOkHttpCallTimeout()));
    }

    @Test
    void recoverableStreamingFailureTreatsWrappedOkHttpCallTimeoutAsRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.isRecoverableStreamingFailure(wrappedOkHttpCallTimeout()));
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

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, wrappedCallerInterruption());

        OpenAiProviderCandidate configuredProvider = routingService
                .selectConfiguredProviderCandidate(githubModelsClient, openAiClient)
                .orElseThrow();
        assertEquals(RateLimitService.ApiProvider.OPENAI, configuredProvider.provider());
    }

    @Test
    void shouldBackoffConfiguredProviderTreats401AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        UnauthorizedException unauthorized =
                UnauthorizedException.builder().headers(headers).build();

        assertTrue(routingService.shouldBackoffConfiguredProvider(unauthorized));
    }

    @Test
    void shouldBackoffConfiguredProviderTreats429AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        RateLimitException rateLimit =
                RateLimitException.builder().headers(headers).build();

        assertTrue(routingService.shouldBackoffConfiguredProvider(rateLimit));
    }

    @Test
    void shouldBackoffConfiguredProviderTreats404AsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();

        assertTrue(routingService.shouldBackoffConfiguredProvider(notFoundException));
    }

    @Test
    void shouldBackoffConfiguredProviderDoesNotBackoffOnGenericRuntime() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.shouldBackoffConfiguredProvider(new IllegalArgumentException("no")));
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
    void primaryBackoffMakesConfiguredProviderUnavailable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.isProviderAvailable(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, PRIMARY_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        assertTrue(routingService
                .selectConfiguredProviderCandidate(null, openAiClient)
                .isEmpty());
    }

    @Test
    void primaryBackoffDoesNotRouteToAlternateProvider() {
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

        assertTrue(routingService
                .selectConfiguredProviderCandidate(githubModelsClient, openAiClient)
                .isEmpty());
    }

    @Test
    void rejectsBlankProviderConfigurationDuringConstruction() {
        IllegalArgumentException configurationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(mock(RateLimitService.class), PRIMARY_BACKOFF_SECONDS, "   "));

        assertEquals(
                "LLM_PRIMARY_PROVIDER must be 'github_models' or 'openai'; received a blank value.",
                configurationFailure.getMessage());
    }

    @Test
    void rejectsUnknownProviderConfigurationDuringConstruction() {
        IllegalArgumentException configurationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class), PRIMARY_BACKOFF_SECONDS, "unsupported-provider"));

        assertEquals(
                "LLM_PRIMARY_PROVIDER must be 'github_models' or 'openai'; received 'unsupported-provider'.",
                configurationFailure.getMessage());
    }

    private OpenAIIoException wrappedOkHttpCallTimeout() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(OK_HTTP_CALL_TIMEOUT_MESSAGE));
    }

    private OpenAIIoException wrappedCallerInterruption() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(CALLER_INTERRUPTION_MESSAGE));
    }
}
