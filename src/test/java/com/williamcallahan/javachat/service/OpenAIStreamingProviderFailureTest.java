package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    void completionTransportFailureIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(upstreamFailure));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("test", TEST_TEMPERATURE))
                .expectErrorSatisfies(completionFailure -> assertSame(upstreamFailure, completionFailure))
                .verify();

        verify(responseService).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
    }

    @Test
    void headerlessRateLimitCompletionPreservesUpstreamAndAttachesDecisionFailure() {
        RateLimitService rateLimitService = configuredOpenAiRateLimitService();
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        RateLimitException firstHeaderlessRateLimitFailure = headerlessRateLimitFailure();
        RateLimitException secondHeaderlessRateLimitFailure = headerlessRateLimitFailure();
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(firstHeaderlessRateLimitFailure))
                .thenReturn(CompletableFuture.failedFuture(secondHeaderlessRateLimitFailure));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        assertCompletionPreservesUpstreamFailure(streamingService, firstHeaderlessRateLimitFailure);
        assertCompletionPreservesUpstreamFailure(streamingService, secondHeaderlessRateLimitFailure);

        verify(responseService, times(2)).create(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void completionPreservesUpstreamAndAttachesUnexpectedRateLimitRecordingFailure() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        RateLimitException upstreamRateLimitFailure = headerlessRateLimitFailure();
        IllegalStateException rateLimitStateFailure = new IllegalStateException("rate-limit state unavailable");
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(upstreamRateLimitFailure));
        doThrow(rateLimitStateFailure)
                .when(rateLimitService)
                .recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.OPENAI, upstreamRateLimitFailure);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("test", TEST_TEMPERATURE))
                .expectErrorSatisfies(completionFailure -> {
                    assertSame(upstreamRateLimitFailure, completionFailure);
                    assertEquals(1, completionFailure.getSuppressed().length);
                    assertSame(rateLimitStateFailure, completionFailure.getSuppressed()[0]);
                })
                .verify();

        verify(rateLimitService)
                .recordRateLimitFromOpenAiServiceException(
                        RateLimitService.ApiProvider.OPENAI, upstreamRateLimitFailure);
    }

    @Test
    void unusableRateLimitHeaderStreamingPreservesUpstreamAndAttachesDecisionFailure() {
        RateLimitService rateLimitService = configuredOpenAiRateLimitService();
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        Headers unusableRateLimitHeaders =
                Headers.builder().put("Retry-After", "not-a-duration").build();
        RateLimitException firstUnusableRateLimitFailure =
                RateLimitException.builder().headers(unusableRateLimitHeaders).build();
        RateLimitException secondUnusableRateLimitFailure =
                RateLimitException.builder().headers(unusableRateLimitHeaders).build();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(failedAsyncStream(firstUnusableRateLimitFailure))
                .thenReturn(failedAsyncStream(secondUnusableRateLimitFailure));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        assertStreamingPreservesUpstreamFailure(streamingService, firstUnusableRateLimitFailure);
        assertStreamingPreservesUpstreamFailure(streamingService, secondUnusableRateLimitFailure);

        verify(responseService, times(2)).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void subscribedTerminalStreamFailureLogsOneBoundedAlert() {
        String upstreamSecretBody = "OPENAI_API_KEY=secret-body";
        RateLimitService rateLimitService = configuredOpenAiRateLimitService();
        OpenAIStreamingService streamingService = configuredStreamingService(rateLimitService);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        ErrorObject upstreamError = ErrorObject.builder()
                .message(upstreamSecretBody)
                .code("queue_upstream_timeout")
                .param(Optional.empty())
                .type("upstream_timeout")
                .build();
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .error(upstreamError)
                .build();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(upstreamFailure);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), TEST_TEMPERATURE)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(streamingFailure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, streamingFailure);
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        List<ILoggingEvent> terminalAlerts = streamingFailureLogEvents.events().stream()
                .filter(loggingEvent -> loggingEvent.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, terminalAlerts.size());
        ILoggingEvent terminalAlert = terminalAlerts.getFirst();
        assertNull(terminalAlert.getThrowableProxy());
        assertFalse(terminalAlert.getFormattedMessage().contains(upstreamSecretBody));
        assertFalse(terminalAlert.toString().contains(upstreamSecretBody));
    }

    @Test
    void unavailableStreamDefersErrorSeverityToRequestBoundary() {
        OpenAIStreamingService streamingService = configuredStreamingService(mock(RateLimitService.class));

        IllegalStateException unavailableFailure = assertThrows(IllegalStateException.class, () -> streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), TEST_TEMPERATURE)
                .block());

        assertFalse(streamingService.isRecoverableStreamingFailure(unavailableFailure));
        assertEquals(0, serviceLogCount(Level.ERROR, "LLM providers unavailable"));
        assertEquals(1, serviceLogCount(Level.WARN, "LLM providers unavailable"));
    }

    private static OpenAIStreamingService configuredStreamingService(RateLimitService rateLimitService) {
        return new OpenAIStreamingService(
                rateLimitService,
                testRequestFactory(),
                configuredProviderRoutingService(rateLimitService),
                new OpenAiStreamingFailureReporter());
    }

    private static ResponseServiceAsync mockAsyncResponseService(OpenAIClient client) {
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responseService = mock(ResponseServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.responses()).thenReturn(responseService);
        return responseService;
    }

    private static AsyncStreamResponse<ResponseStreamEvent> failedAsyncStream(Throwable upstreamFailure) {
        return new AsyncStreamResponse<>() {
            private final CompletableFuture<Void> completionFuture = CompletableFuture.failedFuture(upstreamFailure);

            @Override
            public AsyncStreamResponse<ResponseStreamEvent> subscribe(
                    AsyncStreamResponse.Handler<? super ResponseStreamEvent> responseEventHandler) {
                responseEventHandler.onComplete(Optional.of(upstreamFailure));
                return this;
            }

            @Override
            public AsyncStreamResponse<ResponseStreamEvent> subscribe(
                    AsyncStreamResponse.Handler<? super ResponseStreamEvent> responseEventHandler,
                    Executor callbackExecutor) {
                callbackExecutor.execute(() -> responseEventHandler.onComplete(Optional.of(upstreamFailure)));
                return this;
            }

            @Override
            public CompletableFuture<Void> onCompleteFuture() {
                return completionFuture;
            }

            @Override
            public void close() {}
        };
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
        return new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.4", configuredLlmProperties());
    }

    private static OpenAiProviderRoutingService configuredProviderRoutingService(RateLimitService rateLimitService) {
        return new OpenAiProviderRoutingService(rateLimitService, configuredLlmProperties());
    }

    private static RateLimitService configuredOpenAiRateLimitService() {
        RateLimitState rateLimitState = mock(RateLimitState.class);
        when(rateLimitState.isAvailable(RateLimitService.ApiProvider.OPENAI.getName()))
                .thenReturn(true);
        MockEnvironment configuredEnvironment =
                new MockEnvironment().withProperty("OPENAI_API_KEY", CONFIGURED_PROVIDER_API_KEY);
        return new RateLimitService(rateLimitState, configuredEnvironment);
    }

    private long serviceLogCount(Level level, String messageFragment) {
        return serviceLogEvents.events().stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }
}
