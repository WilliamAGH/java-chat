package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseRefusalDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ResponseService;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies terminal provider transport failures, reservation enforcement, and completion validation.
 *
 * <p>Provider routing policy is verified in {@link OpenAiProviderRoutingServiceTest} so this
 * suite stays focused on SDK transport and stream-facing behavior.</p>
 */
class OpenAIStreamingServiceTest {
    private static final String CONFIGURED_PROVIDER_API_KEY = "configured-provider-api-key";
    private static final String STALE_UNSELECTED_PROVIDER_API_KEY = "stale-unselected-provider-api-key";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String GITHUB_MODELS_BASE_URL = "https://models.github.ai/inference/v1";
    private static final String INVALID_UNSELECTED_PROVIDER_BASE_URL = " ";
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OpenAIStreamingService.class);
    private ExpectedLogEvents serviceLogEvents;
    private final Logger streamingFailureLogger =
            (Logger) LoggerFactory.getLogger(OpenAiStreamingFailureException.class);
    private ExpectedLogEvents streamingFailureLogEvents;
    private final Logger providerRoutingLogger = (Logger) LoggerFactory.getLogger(OpenAiProviderRoutingService.class);
    private ExpectedLogEvents providerRoutingLogEvents;

    @BeforeEach
    void captureServiceLogs() {
        serviceLogEvents = ExpectedLogEvents.capture(serviceLogger);
        streamingFailureLogEvents = ExpectedLogEvents.capture(streamingFailureLogger);
        providerRoutingLogEvents = ExpectedLogEvents.capture(providerRoutingLogger);
    }

    @AfterEach
    void stopCapturingServiceLogs() {
        providerRoutingLogEvents.close();
        streamingFailureLogEvents.close();
        serviceLogEvents.close();
    }

    private OpenAIStreamingService createStreamingService() {
        return createStreamingService(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    private OpenAIStreamingService createStreamingService(RateLimitService.ApiProvider configuredProvider) {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, configuredProvider);
        return new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
    }

    @Test
    void availabilityRejectsOpenAiCredentialForConfiguredGithubModelsProvider() {
        OpenAIStreamingService streamingService = createStreamingService(RateLimitService.ApiProvider.GITHUB_MODELS);
        ReflectionTestUtils.setField(streamingService, "openAiClient", mock(OpenAIClient.class));
        ReflectionTestUtils.setField(streamingService, "isAvailable", true);

        assertFalse(streamingService.isAvailable());
    }

    @Test
    void availabilityRejectsGithubModelsCredentialForConfiguredOpenAiProvider() {
        OpenAIStreamingService streamingService = createStreamingService(RateLimitService.ApiProvider.OPENAI);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", mock(OpenAIClient.class));
        ReflectionTestUtils.setField(streamingService, "isAvailable", true);

        assertFalse(streamingService.isAvailable());
    }

    @Test
    void initializationIgnoresInvalidGithubModelsSettingsWhenOpenAiIsConfigured() {
        OpenAIStreamingService streamingService = createStreamingService(RateLimitService.ApiProvider.OPENAI);
        ReflectionTestUtils.setField(streamingService, "openaiApiKey", CONFIGURED_PROVIDER_API_KEY);
        ReflectionTestUtils.setField(streamingService, "openaiBaseUrl", OPENAI_BASE_URL);
        ReflectionTestUtils.setField(streamingService, "githubToken", STALE_UNSELECTED_PROVIDER_API_KEY);
        ReflectionTestUtils.setField(streamingService, "githubModelsBaseUrl", INVALID_UNSELECTED_PROVIDER_BASE_URL);

        try {
            assertDoesNotThrow(streamingService::initializeClient);
            assertTrue(streamingService.isAvailable());
        } finally {
            streamingService.shutdown();
        }
    }

    @Test
    void initializationIgnoresInvalidOpenAiSettingsWhenGithubModelsIsConfigured() {
        OpenAIStreamingService streamingService = createStreamingService(RateLimitService.ApiProvider.GITHUB_MODELS);
        ReflectionTestUtils.setField(streamingService, "githubToken", CONFIGURED_PROVIDER_API_KEY);
        ReflectionTestUtils.setField(streamingService, "githubModelsBaseUrl", GITHUB_MODELS_BASE_URL);
        ReflectionTestUtils.setField(streamingService, "openaiApiKey", STALE_UNSELECTED_PROVIDER_API_KEY);
        ReflectionTestUtils.setField(streamingService, "openaiBaseUrl", INVALID_UNSELECTED_PROVIDER_BASE_URL);

        try {
            assertDoesNotThrow(streamingService::initializeClient);
            assertTrue(streamingService.isAvailable());
        } finally {
            streamingService.shutdown();
        }
    }

    @Test
    void recoverableStreamingFailureUnwrapsNestedTerminalContext() {
        OpenAIStreamingService streamingService = createStreamingService();
        InternalServerException internalServerException = InternalServerException.builder()
                .statusCode(503)
                .headers(Headers.builder().build())
                .build();
        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                internalServerException,
                new StreamingFailureReporter.TerminalAttempt(
                        RateLimitService.ApiProvider.OPENAI.getName(), "gpt-5.2", 1, 1, false));

        assertTrue(streamingService.isRecoverableStreamingFailure(
                new IllegalStateException("reactor boundary", terminalFailure)));
    }

    @Test
    void subscribedTerminalStreamFailureLogsOneBoundedAlert() {
        String upstreamSecretBody = "OPENAI_API_KEY=secret-body";
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
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
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(upstreamFailure);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
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
    void emptyTextDeltaBeforeStreamingFailureIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent emptyTextEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent emptyTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerStream);
        when(emptyTextEvent.outputTextDelta()).thenReturn(Optional.of(emptyTextDelta));
        when(emptyTextDelta.delta()).thenReturn("");
        when(providerStream.stream())
                .thenAnswer(ignoredInvocation -> Stream.concat(Stream.of(emptyTextEvent), Stream.generate(() -> {
                    throw upstreamFailure;
                })));
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(emptyTextDelta).delta();
    }

    @Test
    void providerStreamWithoutVisibleTextIsTerminal() {
        for (String invisibleText : List.of("", " \t\n\u200B\uFEFF\u2060")) {
            RateLimitService rateLimitService = mock(RateLimitService.class);
            StreamResponse<ResponseStreamEvent> providerStream = mock();
            ResponseStreamEvent invisibleTextEvent = mock(ResponseStreamEvent.class);
            ResponseTextDeltaEvent invisibleTextDelta = mock(ResponseTextDeltaEvent.class);
            when(invisibleTextEvent.outputTextDelta()).thenReturn(Optional.of(invisibleTextDelta));
            when(invisibleTextDelta.delta()).thenReturn(invisibleText);
            when(providerStream.stream())
                    .thenAnswer(ignoredInvocation -> Stream.of(invisibleTextEvent, completedStreamEvent()));
            OpenAIStreamingService streamingService =
                    streamingServiceForProviderStream(rateLimitService, providerStream);

            StepVerifier.create(streamingService
                            .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                            .flatMapMany(StreamingResult::textChunks))
                    .expectNext(invisibleText)
                    .expectErrorSatisfies(failure ->
                            assertOpenAiUpstreamFailure(failure, "Provider stream completed without visible text"))
                    .verify();

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
        }
    }

    @Test
    void terminalProviderEventsFailTheStreamBeforeSuccess() {
        ResponseStreamEvent errorStreamEvent = mock(ResponseStreamEvent.class);
        when(errorStreamEvent.error()).thenReturn(Optional.of(mock(ResponseErrorEvent.class)));
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        ResponseFailedEvent failedEvent = mock(ResponseFailedEvent.class);
        when(failedEvent.response()).thenReturn(mock(Response.class));
        when(failedStreamEvent.failed()).thenReturn(Optional.of(failedEvent));
        ResponseStreamEvent incompleteStreamEvent = mock(ResponseStreamEvent.class);
        ResponseIncompleteEvent incompleteEvent = mock(ResponseIncompleteEvent.class);
        when(incompleteEvent.response()).thenReturn(mock(Response.class));
        when(incompleteStreamEvent.incomplete()).thenReturn(Optional.of(incompleteEvent));

        for (ResponseStreamEvent providerTerminalEvent :
                List.of(errorStreamEvent, failedStreamEvent, incompleteStreamEvent)) {
            RateLimitService rateLimitService = mock(RateLimitService.class);
            StreamResponse<ResponseStreamEvent> providerStream = mock();
            when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(providerTerminalEvent));
            OpenAIStreamingService streamingService =
                    streamingServiceForProviderStream(rateLimitService, providerStream);

            StepVerifier.create(streamingService
                            .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                            .flatMapMany(StreamingResult::textChunks))
                    .expectErrorSatisfies(failure -> assertInstanceOf(
                            OpenAIException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure()))
                    .verify();

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
        }
    }

    @Test
    void visibleProviderTextWithoutCompletedEventIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent visibleTextEvent = visibleTextStreamEvent("truncated response");
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(visibleTextEvent));
        OpenAIStreamingService streamingService = streamingServiceForProviderStream(rateLimitService, providerStream);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("truncated response")
                .expectErrorSatisfies(failure -> {
                    OpenAiResponseStreamException upstreamFailure = assertInstanceOf(
                            OpenAiResponseStreamException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(
                            OpenAiResponseStreamException.TerminalReason.MISSING_COMPLETION,
                            upstreamFailure.terminalReason());
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    @Test
    void visibleProviderTextCompletesSuccessfully() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent visibleTextEvent = visibleTextStreamEvent("visible response");
        when(providerStream.stream())
                .thenAnswer(ignoredInvocation -> Stream.of(visibleTextEvent, completedStreamEvent()));
        OpenAIStreamingService streamingService = streamingServiceForProviderStream(rateLimitService, providerStream);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("visible response")
                .verifyComplete();

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    @Test
    void refusalTextCompletesAsVisibleAssistantOutput() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent refusalStreamEvent = mock(ResponseStreamEvent.class);
        ResponseRefusalDeltaEvent refusalDeltaEvent = mock(ResponseRefusalDeltaEvent.class);
        when(refusalStreamEvent.refusalDelta()).thenReturn(Optional.of(refusalDeltaEvent));
        when(refusalDeltaEvent.delta()).thenReturn("I cannot help with that request.");
        when(providerStream.stream())
                .thenAnswer(ignoredInvocation -> Stream.of(refusalStreamEvent, completedStreamEvent()));
        OpenAIStreamingService streamingService = streamingServiceForProviderStream(rateLimitService, providerStream);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("I cannot help with that request.")
                .verifyComplete();

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    @Test
    void failedServerResponseStartsBackoffAndRemainsRetryable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        ResponseError providerError = mock(ResponseError.class);
        when(providerError.code()).thenReturn(ResponseError.Code.SERVER_ERROR);
        Response failedResponse = mock(Response.class);
        when(failedResponse.error()).thenReturn(Optional.of(providerError));
        ResponseFailedEvent failedEvent = mock(ResponseFailedEvent.class);
        when(failedEvent.response()).thenReturn(failedResponse);
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        when(failedStreamEvent.failed()).thenReturn(Optional.of(failedEvent));
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(failedStreamEvent));
        OpenAIStreamingService streamingService = streamingServiceForProviderStream(rateLimitService, providerStream);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiResponseStreamException upstreamFailure = assertInstanceOf(
                            OpenAiResponseStreamException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(
                            OpenAiResponseStreamException.TerminalReason.SERVER_ERROR,
                            upstreamFailure.terminalReason());
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();
        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    @Test
    void failedRateLimitResponseStartsBackoffWithoutImmediateRetry() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        ResponseError providerError = mock(ResponseError.class);
        when(providerError.code()).thenReturn(ResponseError.Code.RATE_LIMIT_EXCEEDED);
        Response failedResponse = mock(Response.class);
        when(failedResponse.error()).thenReturn(Optional.of(providerError));
        ResponseFailedEvent failedEvent = mock(ResponseFailedEvent.class);
        when(failedEvent.response()).thenReturn(failedResponse);
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        when(failedStreamEvent.failed()).thenReturn(Optional.of(failedEvent));
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(failedStreamEvent));
        OpenAIStreamingService streamingService = streamingServiceForProviderStream(rateLimitService, providerStream);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    OpenAiResponseStreamException upstreamFailure = assertInstanceOf(
                            OpenAiResponseStreamException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(
                            OpenAiResponseStreamException.TerminalReason.RATE_LIMIT_EXCEEDED,
                            upstreamFailure.terminalReason());
                    assertFalse(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();
        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
    }

    @Test
    void incompleteResponseReasonsRemainDistinctAndNonRetryable() {
        for (Response.IncompleteDetails.Reason incompleteReason : List.of(
                Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS,
                Response.IncompleteDetails.Reason.CONTENT_FILTER)) {
            RateLimitService rateLimitService = mock(RateLimitService.class);
            Response.IncompleteDetails incompleteDetails = mock(Response.IncompleteDetails.class);
            when(incompleteDetails.reason()).thenReturn(Optional.of(incompleteReason));
            Response incompleteResponse = mock(Response.class);
            when(incompleteResponse.incompleteDetails()).thenReturn(Optional.of(incompleteDetails));
            ResponseIncompleteEvent incompleteEvent = mock(ResponseIncompleteEvent.class);
            when(incompleteEvent.response()).thenReturn(incompleteResponse);
            ResponseStreamEvent incompleteStreamEvent = mock(ResponseStreamEvent.class);
            when(incompleteStreamEvent.incomplete()).thenReturn(Optional.of(incompleteEvent));
            StreamResponse<ResponseStreamEvent> providerStream = mock();
            when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(incompleteStreamEvent));
            OpenAIStreamingService streamingService =
                    streamingServiceForProviderStream(rateLimitService, providerStream);

            StepVerifier.create(streamingService
                            .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                            .flatMapMany(StreamingResult::textChunks))
                    .expectErrorSatisfies(failure -> {
                        OpenAiResponseStreamException upstreamFailure = assertInstanceOf(
                                OpenAiResponseStreamException.class,
                                assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                        .upstreamFailure());
                        OpenAiResponseStreamException.TerminalReason expectedTerminalReason =
                                incompleteReason == Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS
                                        ? OpenAiResponseStreamException.TerminalReason.MAX_OUTPUT_TOKENS
                                        : OpenAiResponseStreamException.TerminalReason.CONTENT_FILTER;
                        assertEquals(expectedTerminalReason, upstreamFailure.terminalReason());
                        assertFalse(streamingService.isRecoverableStreamingFailure(failure));
                    })
                    .verify();

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.GITHUB_MODELS);
        }
    }

    @Test
    void deniedConfiguredProviderReservationTerminatesStreamingAndCompletionAsRetryableBeforeAnyClientDispatch() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(false);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectErrorSatisfies(failure -> {
                    assertInstanceOf(ConfiguredProviderTemporarilyUnavailableException.class, failure);
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();
        StepVerifier.create(streamingService.complete("test", 0.7))
                .expectErrorSatisfies(failure -> {
                    assertInstanceOf(ConfiguredProviderTemporarilyUnavailableException.class, failure);
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        verify(rateLimitService, times(2)).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(githubModelsClient, openAiClient);
    }

    @Test
    void cooldownRecordedAfterStreamCreationPreventsDispatchAtTextSubscription() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        StreamingResult streamingResult = Objects.requireNonNull(streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());
        InternalServerException gatewayTimeout = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        providerRoutingService.recordProviderFailure(RateLimitService.ApiProvider.GITHUB_MODELS, gatewayTimeout);

        StepVerifier.create(streamingResult.textChunks())
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();

        verifyNoInteractions(githubModelsClient);
    }

    @Test
    void preTextStreamingTransportFailureIsTerminalAndDoesNotDispatchAlternateProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseService githubModelsResponseService = mock(ResponseService.class);
        InternalServerException githubModelsFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(githubModelsResponseService);
        when(githubModelsResponseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(githubModelsFailure);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StreamingResult streamingResult = Objects.requireNonNull(streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());

        assertEquals(RateLimitService.ApiProvider.GITHUB_MODELS, streamingResult.provider());
        StepVerifier.create(streamingResult.textChunks())
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(githubModelsFailure, terminalFailure.upstreamFailure());
                })
                .verify();
        verify(githubModelsResponseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS);
        verify(rateLimitService, never()).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(openAiClient);
    }

    @Test
    void streamingFailureAfterTextIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent visibleTextEvent = mock(ResponseStreamEvent.class);
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent visibleTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerStream);
        when(visibleTextEvent.outputTextDelta()).thenReturn(Optional.of(visibleTextDelta));
        when(visibleTextDelta.delta()).thenReturn("first token");
        when(failedStreamEvent.outputTextDelta()).thenThrow(upstreamFailure);
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(visibleTextEvent, failedStreamEvent));
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("first token")
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();

        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(visibleTextDelta).delta();
    }

    @Test
    void unavailableStreamDefersErrorSeverityToRequestBoundary() {
        OpenAIStreamingService streamingService = createStreamingService();

        IllegalStateException unavailableFailure = assertThrows(IllegalStateException.class, () -> streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());

        assertFalse(streamingService.isRecoverableStreamingFailure(unavailableFailure));
        assertEquals(0, logCount(Level.ERROR, "LLM providers unavailable"));
        assertEquals(1, logCount(Level.WARN, "LLM providers unavailable"));
    }

    @Test
    void invalidCompletionConfigurationFailsOnSubscription() {
        OpenAIStreamingService streamingService = createStreamingService();

        assertCompletionFailure(streamingService.complete("prompt", 0.7, 0), "maximumOutputTokens must be positive");
        assertCompletionFailure(streamingService.complete("prompt", 0.7, -1), "maximumOutputTokens must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, null), "requestTimeout must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, Duration.ZERO),
                "requestTimeout must be positive");
        assertCompletionFailure(
                streamingService.completeJsonObject("prompt", 0.7, 128, Duration.ofSeconds(-1)),
                "requestTimeout must be positive");
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

    private static OpenAIStreamingService streamingServiceForProviderStream(
            RateLimitService rateLimitService, StreamResponse<ResponseStreamEvent> providerStream) {
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.GITHUB_MODELS))
                .thenReturn(true);
        OpenAiProviderRoutingService providerRoutingService =
                configuredProviderRoutingService(rateLimitService, RateLimitService.ApiProvider.GITHUB_MODELS);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient githubModelsClient = mock(OpenAIClient.class);
        ResponseService responseService = mock(ResponseService.class);
        when(githubModelsClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerStream);
        ReflectionTestUtils.setField(streamingService, "githubModelsClient", githubModelsClient);
        return streamingService;
    }

    private static ResponseStreamEvent visibleTextStreamEvent(String visibleText) {
        ResponseStreamEvent visibleTextEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent visibleTextDelta = mock(ResponseTextDeltaEvent.class);
        when(visibleTextEvent.outputTextDelta()).thenReturn(Optional.of(visibleTextDelta));
        when(visibleTextDelta.delta()).thenReturn(visibleText);
        return visibleTextEvent;
    }

    private static ResponseStreamEvent completedStreamEvent() {
        ResponseStreamEvent completedStreamEvent = mock(ResponseStreamEvent.class);
        when(completedStreamEvent.completed()).thenReturn(Optional.of(mock(ResponseCompletedEvent.class)));
        return completedStreamEvent;
    }

    private static void assertOpenAiUpstreamFailure(Throwable failure, String expectedMessage) {
        OpenAiStreamingFailureException terminalFailure =
                assertInstanceOf(OpenAiStreamingFailureException.class, failure);
        OpenAIException upstreamFailure = assertInstanceOf(OpenAIException.class, terminalFailure.upstreamFailure());
        assertEquals(expectedMessage, upstreamFailure.getMessage());
    }

    private static void assertCompletionFailure(Mono<String> completion, String expectedFailureMessage) {
        StepVerifier.create(completion)
                .expectErrorMatches(failure -> failure instanceof IllegalArgumentException
                        && expectedFailureMessage.equals(failure.getMessage()))
                .verify();
    }

    private long logCount(Level level, String messageFragment) {
        return serviceLogEvents.events().stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }
}
