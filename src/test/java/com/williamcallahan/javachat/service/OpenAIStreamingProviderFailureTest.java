package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

/** Verifies provider failures remain the terminal streaming and completion errors. */
class OpenAIStreamingProviderFailureTest {
    private static final String CONFIGURED_PROVIDER_API_KEY = "configured-provider-api-key";
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final double TEST_TEMPERATURE = 0.7;
    private static final String RATE_LIMIT_HEADERS_MISSING_MESSAGE = "OpenAI rate-limit headers are missing";
    private static final String RATE_LIMIT_HEADERS_INVALID_MESSAGE = "OpenAI rate-limit headers are invalid";

    private ExpectedLogEvents serviceLogEvents;
    private ExpectedLogEvents providerRoutingLogEvents;
    private ExpectedLogEvents streamingFailureLogEvents;

    @BeforeEach
    void captureExpectedFailureLogs() {
        serviceLogEvents = ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(OpenAIStreamingService.class));
        providerRoutingLogEvents =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(OpenAiProviderRoutingService.class));
        streamingFailureLogEvents =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(OpenAiStreamingFailureException.class));
    }

    @AfterEach
    void stopCapturingExpectedFailureLogs() {
        streamingFailureLogEvents.close();
        providerRoutingLogEvents.close();
        serviceLogEvents.close();
    }

    @Test
    void completionTransportFailureIsTerminalAndDoesNotDispatchAlternateProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        InternalServerException githubModelsFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(githubModelsFailure);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("test", TEST_TEMPERATURE))
                .expectErrorSatisfies(completionFailure -> assertSame(githubModelsFailure, completionFailure))
                .verify();

        verify(githubModelsResponseService).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(openAiClient);
    }

    @Test
    void headerlessRateLimitCompletionPreservesUpstreamAndAttachesDecisionFailure() {
        RateLimitService rateLimitService = configuredGithubModelsRateLimitService();
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        RateLimitException firstHeaderlessRateLimitFailure = headerlessRateLimitFailure();
        RateLimitException secondHeaderlessRateLimitFailure = headerlessRateLimitFailure();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(firstHeaderlessRateLimitFailure, secondHeaderlessRateLimitFailure);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        assertCompletionPreservesUpstreamFailure(streamingService, firstHeaderlessRateLimitFailure);
        assertCompletionPreservesUpstreamFailure(streamingService, secondHeaderlessRateLimitFailure);

        verify(githubModelsResponseService, times(2))
                .create(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void unusableRateLimitHeaderStreamingPreservesUpstreamAndAttachesDecisionFailure() {
        RateLimitService rateLimitService = configuredGithubModelsRateLimitService();
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        Headers unusableRateLimitHeaders =
                Headers.builder().put("Retry-After", "not-a-duration").build();
        RateLimitException firstUnusableRateLimitFailure =
                RateLimitException.builder().headers(unusableRateLimitHeaders).build();
        RateLimitException secondUnusableRateLimitFailure =
                RateLimitException.builder().headers(unusableRateLimitHeaders).build();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(firstUnusableRateLimitFailure, secondUnusableRateLimitFailure);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        assertStreamingPreservesUpstreamFailure(streamingService, firstUnusableRateLimitFailure);
        assertStreamingPreservesUpstreamFailure(streamingService, secondUnusableRateLimitFailure);

        verify(githubModelsResponseService, times(2))
                .createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    private static OpenAIStreamingService configuredStreamingService(RateLimitService rateLimitService) {
        return new OpenAIStreamingService(
                rateLimitService,
                testRequestFactory(),
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS),
                new OpenAiStreamingFailureReporter());
    }

    private static RateLimitException headerlessRateLimitFailure() {
        return RateLimitException.builder().headers(Headers.builder().build()).build();
    }

    private static void assertCompletionPreservesUpstreamFailure(
            OpenAIStreamingService streamingService, RateLimitException expectedUpstreamFailure) {
        StepVerifier.create(streamingService.complete("test", TEST_TEMPERATURE))
                .expectErrorSatisfies(completionFailure -> {
                    assertSame(expectedUpstreamFailure, completionFailure);
                    assertSuppressedDecisionFailure(expectedUpstreamFailure, RATE_LIMIT_HEADERS_MISSING_MESSAGE);
                })
                .verify();
    }

    private static void assertStreamingPreservesUpstreamFailure(
            OpenAIStreamingService streamingService, RateLimitException expectedUpstreamFailure) {
        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), TEST_TEMPERATURE)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(streamingFailure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, streamingFailure);
                    assertSame(expectedUpstreamFailure, terminalFailure.upstreamFailure());
                    assertSuppressedDecisionFailure(expectedUpstreamFailure, RATE_LIMIT_HEADERS_INVALID_MESSAGE);
                })
                .verify();
    }

    private static void assertSuppressedDecisionFailure(
            RateLimitException upstreamFailure, String expectedDecisionFailureMessage) {
        assertEquals(1, upstreamFailure.getSuppressed().length);
        RateLimitDecisionException decisionFailure = assertInstanceOf(
                RateLimitDecisionException.class, upstreamFailure.getSuppressed()[0]);
        assertEquals(expectedDecisionFailureMessage, decisionFailure.getMessage());
    }

    private static AppProperties configuredLlmProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setCompletionOutputTokenBudget(TEST_COMPLETION_OUTPUT_TOKEN_BUDGET);
        appProperties.getLlm().setConfiguredProviderBackoffSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS);
        return appProperties;
    }

    private static OpenAiRequestFactory testRequestFactory() {
        return new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", configuredLlmProperties());
    }

    private static OpenAiProviderRoutingService configuredProviderRoutingService(
            RateLimitService rateLimitService, RateLimitService.ApiProvider configuredProvider) {
        return new OpenAiProviderRoutingService(
                rateLimitService, configuredLlmProperties(), configuredProvider.getName());
    }

    private static RateLimitService configuredGithubModelsRateLimitService() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        when(rateLimitState.isAvailable(RateLimitService.ApiProvider.GITHUB_MODELS.getName()))
                .thenReturn(true);
        MockEnvironment configuredEnvironment =
                new MockEnvironment().withProperty("GITHUB_TOKEN", CONFIGURED_PROVIDER_API_KEY);
        return new RateLimitService(rateLimitState, configuredEnvironment);
    }
}
