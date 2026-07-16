package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
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
 * Verifies configured-provider validation, backoff, and failure classification policy.
 *
 * <p>This suite targets routing directly so streaming transport tests remain focused on stream behavior.</p>
 */
class OpenAiProviderRoutingServiceTest {
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final String OPENAI_REQUEST_FAILED_MESSAGE = "Request failed";
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";
    private static final String CALLER_INTERRUPTION_MESSAGE = "request interrupted by caller timeout";

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(
                rateLimitService,
                CONFIGURED_PROVIDER_BACKOFF_SECONDS,
                RateLimitService.ApiProvider.GITHUB_MODELS.getName());
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
    void callerCancellationKeepsConfiguredProviderEligible() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, CONFIGURED_PROVIDER_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, wrappedCallerInterruption());

        OpenAiProviderCandidate configuredProvider = routingService
                .admitConfiguredProviderRequest(githubModelsClient, openAiClient)
                .orElseThrow();
        assertEquals(RateLimitService.ApiProvider.OPENAI, configuredProvider.provider());
    }

    @Test
    void permanentProviderFailuresDoNotCreateRetryableCooldown() {
        Headers headers = Headers.builder().build();
        List<RuntimeException> permanentProviderFailures = List.of(
                UnauthorizedException.builder().headers(headers).build(),
                PermissionDeniedException.builder().headers(headers).build(),
                NotFoundException.builder().headers(headers).build());

        for (RuntimeException permanentProviderFailure : permanentProviderFailures) {
            RateLimitService rateLimitService = mock(RateLimitService.class);
            when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                    .thenReturn(true);
            OpenAiProviderRoutingService routingService = createRoutingService(rateLimitService);
            OpenAIClient githubModelsClient = mock(OpenAIClient.class);

            routingService.recordProviderFailure(RateLimitService.ApiProvider.GITHUB_MODELS, permanentProviderFailure);

            OpenAiProviderCandidate providerAdmission = routingService
                    .admitConfiguredProviderRequest(githubModelsClient, null)
                    .orElseThrow();
            assertEquals(RateLimitService.ApiProvider.GITHUB_MODELS, providerAdmission.provider());
            assertFalse(routingService.shouldBackoffConfiguredProvider(permanentProviderFailure));
            assertFalse(routingService.isRecoverableStreamingFailure(permanentProviderFailure));
        }
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
    void gatewayTimeoutBackoffMakesNextConfiguredProviderRequestRetryable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, CONFIGURED_PROVIDER_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        InternalServerException gatewayTimeout = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeout);

        ConfiguredProviderTemporarilyUnavailableException temporaryFailure = assertThrows(
                ConfiguredProviderTemporarilyUnavailableException.class,
                () -> routingService.admitConfiguredProviderRequest(null, openAiClient));

        assertEquals(RateLimitService.ApiProvider.OPENAI, temporaryFailure.provider());
        assertTrue(routingService.isRecoverableStreamingFailure(temporaryFailure));
    }

    @Test
    void configuredProviderBackoffDoesNotRouteToAlternateProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = new OpenAiProviderRoutingService(
                rateLimitService, CONFIGURED_PROVIDER_BACKOFF_SECONDS, RateLimitService.ApiProvider.OPENAI.getName());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

        assertThrows(
                ConfiguredProviderTemporarilyUnavailableException.class,
                () -> routingService.admitConfiguredProviderRequest(githubModelsClient, openAiClient));
    }

    @Test
    void rateLimitAdmissionDenialIsRetryable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(false);
        OpenAiProviderRoutingService routingService = createRoutingService(rateLimitService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        ConfiguredProviderTemporarilyUnavailableException temporaryFailure = assertThrows(
                ConfiguredProviderTemporarilyUnavailableException.class,
                () -> routingService.admitConfiguredProviderRequest(githubModelsClient, null));

        assertEquals(RateLimitService.ApiProvider.GITHUB_MODELS, temporaryFailure.provider());
        assertTrue(routingService.isRecoverableStreamingFailure(temporaryFailure));
    }

    @Test
    void rejectsBlankProviderConfigurationDuringConstruction() {
        IllegalArgumentException configurationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class), CONFIGURED_PROVIDER_BACKOFF_SECONDS, "   "));

        assertEquals(
                "LLM_PRIMARY_PROVIDER must be 'github_models' or 'openai'; received a blank value.",
                configurationFailure.getMessage());
    }

    @Test
    void rejectsUnknownProviderConfigurationDuringConstruction() {
        IllegalArgumentException configurationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class), CONFIGURED_PROVIDER_BACKOFF_SECONDS, "unsupported-provider"));

        assertEquals(
                "LLM_PRIMARY_PROVIDER must be 'github_models' or 'openai'; received 'unsupported-provider'.",
                configurationFailure.getMessage());
    }

    @Test
    void rejectsNonPositiveBackoffConfigurationDuringConstruction() {
        IllegalArgumentException zeroBackoffFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class), 0, RateLimitService.ApiProvider.OPENAI.getName()));
        IllegalArgumentException negativeBackoffFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class), -1, RateLimitService.ApiProvider.OPENAI.getName()));

        assertEquals("LLM_PRIMARY_BACKOFF_SECONDS must be positive; received 0.", zeroBackoffFailure.getMessage());
        assertEquals("LLM_PRIMARY_BACKOFF_SECONDS must be positive; received -1.", negativeBackoffFailure.getMessage());
    }

    private OpenAIIoException wrappedOkHttpCallTimeout() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(OK_HTTP_CALL_TIMEOUT_MESSAGE));
    }

    private OpenAIIoException wrappedCallerInterruption() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(CALLER_INTERRUPTION_MESSAGE));
    }

    private OpenAiProviderRoutingService createRoutingService(RateLimitService rateLimitService) {
        return new OpenAiProviderRoutingService(
                rateLimitService,
                CONFIGURED_PROVIDER_BACKOFF_SECONDS,
                RateLimitService.ApiProvider.GITHUB_MODELS.getName());
    }
}
