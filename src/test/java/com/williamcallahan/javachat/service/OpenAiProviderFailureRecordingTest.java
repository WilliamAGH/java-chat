package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.williamcallahan.javachat.config.AppProperties;
import org.junit.jupiter.api.Test;

/** Verifies configured-provider failure recording preserves strict bookkeeping errors. */
class OpenAiProviderFailureRecordingTest {
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;

    @Test
    void rateLimitDecisionFailurePropagatesWithoutStartingConfiguredProviderCooldown() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        RateLimitException headerlessRateLimitFailure =
                RateLimitException.builder().headers(Headers.builder().build()).build();
        RateLimitDecisionException rateLimitDecisionFailure =
                new RateLimitDecisionException("OpenAI rate-limit headers are missing");
        doThrow(rateLimitDecisionFailure)
                .when(rateLimitService)
                .recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.GITHUB_MODELS, headerlessRateLimitFailure);
        OpenAiProviderRoutingService routingService = configuredProviderRoutingService(rateLimitService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);

        RateLimitDecisionException propagatedFailure = assertThrows(
                RateLimitDecisionException.class,
                () -> routingService.recordProviderFailure(
                        RateLimitService.ApiProvider.GITHUB_MODELS, headerlessRateLimitFailure));

        assertSame(rateLimitDecisionFailure, propagatedFailure);
        assertDoesNotThrow(() -> routingService.admitConfiguredProviderRequest(githubModelsClient, null));
    }

    private static OpenAiProviderRoutingService configuredProviderRoutingService(RateLimitService rateLimitService) {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setConfiguredProviderBackoffSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS);
        return new OpenAiProviderRoutingService(
                rateLimitService, appProperties, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
    }
}
