package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
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
import com.openai.services.async.ResponseServiceAsync;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureReporter;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.CurrentQuerySegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.prompt.SystemSegment;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
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
    private static final String OPENAI_BASE_URL = "https://api.llm-gateway.iocloudhost.net/v1";
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final String INVISIBLE_PROVIDER_DELTA = " \t\u061C\u202E\u2066\uDB40\uDC01";
    private static final Duration INVISIBLE_PROVIDER_DELTA_INTERVAL = Duration.ofSeconds(3);
    private static final Duration VISIBLE_OUTPUT_DEADLINE_REMAINDER = Duration.ofSeconds(2);
    private static final Duration MID_RESPONSE_VISIBLE_OUTPUT_PAUSE = Duration.ofSeconds(21);
    private static final long STREAMING_REQUEST_TIMEOUT_SECONDS = 20L;
    private static final long DERIVED_WATCHDOG_REQUEST_TIMEOUT_SECONDS = 5L;
    private static final Duration DERIVED_WATCHDOG_REMAINDER = Duration.ofSeconds(2);

    private OpenAIStreamingService createStreamingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        return new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
    }

    @Test
    void availabilityUsesOpenAiClient() {
        OpenAIStreamingService streamingService = createStreamingService();
        ReflectionTestUtils.setField(streamingService, "openAiClient", mock(OpenAIClient.class));
        ReflectionTestUtils.setField(streamingService, "isAvailable", true);

        assertTrue(streamingService.isAvailable());
    }

    @Test
    void initializationUsesOpenAiSettings() {
        OpenAIStreamingService streamingService = createStreamingService();
        ReflectionTestUtils.setField(streamingService, "openaiApiKey", CONFIGURED_PROVIDER_API_KEY);
        ReflectionTestUtils.setField(streamingService, "openaiBaseUrl", OPENAI_BASE_URL);

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
                        RateLimitService.ApiProvider.OPENAI.getName(), "gpt-5.4", 1, 1, false));

        assertTrue(streamingService.isRecoverableStreamingFailure(
                new IllegalStateException("reactor boundary", terminalFailure)));
    }

    @Test
    void emptyTextDeltaBeforeStreamingFailureIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent emptyTextEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent emptyTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(asyncProviderStream(providerStream));
        when(emptyTextEvent.outputTextDelta()).thenReturn(Optional.of(emptyTextDelta));
        when(emptyTextDelta.delta()).thenReturn("");
        when(providerStream.stream())
                .thenAnswer(ignoredInvocation -> Stream.concat(Stream.of(emptyTextEvent), Stream.generate(() -> {
                    throw upstreamFailure;
                })));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectNext("")
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
        for (String invisibleText : List.of("", INVISIBLE_PROVIDER_DELTA)) {
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
                            assertOpenAiUpstreamFailure(failure, "Provider response completed without visible text"))
                    .verify();

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
        }
    }

    @Test
    void invisibleProviderDeltasCannotResetTheVisibleOutputDeadline() {
        OpenAIStreamingService streamingService = createStreamingService();
        ReflectionTestUtils.setField(
                streamingService, "streamingRequestTimeoutSeconds", STREAMING_REQUEST_TIMEOUT_SECONDS);
        AtomicBoolean upstreamCancelled = new AtomicBoolean();

        StepVerifier.withVirtualTime(() -> {
                    Flux<String> invisibleProviderDeltas = Flux.interval(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                            .map(ignoredTick -> INVISIBLE_PROVIDER_DELTA)
                            .doOnCancel(() -> upstreamCancelled.set(true));
                    return streamingService.enforceVisibleOutputDeadline(invisibleProviderDeltas);
                })
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(VISIBLE_OUTPUT_DEADLINE_REMAINDER)
                .expectError(TimeoutException.class)
                .verify();

        assertTrue(upstreamCancelled.get());
    }

    @Test
    void visibleOutputDeadlineStopsAfterFirstVisibleChunk() {
        OpenAIStreamingService streamingService = createStreamingService();

        StepVerifier.withVirtualTime(() -> streamingService.enforceVisibleOutputDeadline(Flux.concat(
                        Mono.just("first visible chunk"),
                        Mono.delay(MID_RESPONSE_VISIBLE_OUTPUT_PAUSE).thenReturn("visible chunk after pause"))))
                .expectNext("first visible chunk")
                .thenAwait(MID_RESPONSE_VISIBLE_OUTPUT_PAUSE)
                .expectNext("visible chunk after pause")
                .verifyComplete();
    }

    @Test
    void visibleOutputDeadlineFollowsTheConfiguredRequestTimeout() {
        OpenAIStreamingService streamingService = createStreamingService();
        ReflectionTestUtils.setField(
                streamingService, "streamingRequestTimeoutSeconds", DERIVED_WATCHDOG_REQUEST_TIMEOUT_SECONDS);

        StepVerifier.withVirtualTime(() -> streamingService.enforceVisibleOutputDeadline(
                        Flux.interval(INVISIBLE_PROVIDER_DELTA_INTERVAL).map(ignoredTick -> INVISIBLE_PROVIDER_DELTA)))
                .thenAwait(INVISIBLE_PROVIDER_DELTA_INTERVAL)
                .expectNext(INVISIBLE_PROVIDER_DELTA)
                .thenAwait(DERIVED_WATCHDOG_REMAINDER)
                .expectError(TimeoutException.class)
                .verify();
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

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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
                    OpenAiResponseException upstreamFailure = assertInstanceOf(
                            OpenAiResponseException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(
                            OpenAiResponseException.TerminalReason.MISSING_COMPLETION,
                            upstreamFailure.terminalReason());
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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
                    OpenAiResponseException upstreamFailure = assertInstanceOf(
                            OpenAiResponseException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(OpenAiResponseException.TerminalReason.SERVER_ERROR, upstreamFailure.terminalReason());
                    assertTrue(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();
        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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
                    OpenAiResponseException upstreamFailure = assertInstanceOf(
                            OpenAiResponseException.class,
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                    .upstreamFailure());
                    assertEquals(
                            OpenAiResponseException.TerminalReason.RATE_LIMIT_EXCEEDED,
                            upstreamFailure.terminalReason());
                    assertFalse(streamingService.isRecoverableStreamingFailure(failure));
                })
                .verify();

        StepVerifier.create(streamingService
                        .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                        .flatMapMany(StreamingResult::textChunks))
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();
        verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
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
                        OpenAiResponseException upstreamFailure = assertInstanceOf(
                                OpenAiResponseException.class,
                                assertInstanceOf(OpenAiStreamingFailureException.class, failure)
                                        .upstreamFailure());
                        OpenAiResponseException.TerminalReason expectedTerminalReason =
                                incompleteReason == Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS
                                        ? OpenAiResponseException.TerminalReason.MAX_OUTPUT_TOKENS
                                        : OpenAiResponseException.TerminalReason.CONTENT_FILTER;
                        assertEquals(expectedTerminalReason, upstreamFailure.terminalReason());
                        assertFalse(streamingService.isRecoverableStreamingFailure(failure));
                    })
                    .verify();

            verify(rateLimitService, never()).recordSuccess(RateLimitService.ApiProvider.OPENAI);
        }
    }

    @Test
    void deniedConfiguredProviderReservationTerminatesStreamingAndCompletionAsRetryableBeforeAnyClientDispatch() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(false);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
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

        verify(rateLimitService, times(2)).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
        verifyNoInteractions(openAiClient);
    }

    @Test
    void cooldownRecordedAfterStreamCreationPreventsDispatchAtTextSubscription() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StreamingResult streamingResult = Objects.requireNonNull(streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());
        InternalServerException gatewayTimeout = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        providerRoutingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeout);

        StepVerifier.create(streamingResult.textChunks())
                .expectError(ConfiguredProviderTemporarilyUnavailableException.class)
                .verify();

        verifyNoInteractions(openAiClient);
    }

    @Test
    void exposesOnlyDocumentIdentitiesRetainedByGpt54Truncation() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);
        StructuredPrompt oversizedPrompt = new StructuredPrompt(
                new SystemSegment("System instructions", 100),
                List.of(
                        new ContextDocumentSegment(
                                1,
                                "truncated-context-document",
                                "https://example.test/truncated",
                                "Oversized reference",
                                120_000),
                        new ContextDocumentSegment(
                                2,
                                "retained-context-document",
                                "https://example.test/retained",
                                "Retained reference",
                                100)),
                List.of(),
                new CurrentQuerySegment("Explain the retained reference", 50));

        StreamingResult streamingResult = Objects.requireNonNull(
                streamingService.streamResponse(oversizedPrompt, 0.7).block());

        assertEquals(List.of("retained-context-document"), streamingResult.contextDocumentIds());
        verifyNoInteractions(openAiClient);
    }

    @Test
    void preTextStreamingTransportFailureIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(upstreamFailure);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StreamingResult streamingResult = Objects.requireNonNull(streamingService
                .streamResponse(StructuredPrompt.fromRawPrompt("test", 1), 0.7)
                .block());

        assertEquals(RateLimitService.ApiProvider.OPENAI, streamingResult.provider());
        StepVerifier.create(streamingResult.textChunks())
                .expectErrorSatisfies(failure -> {
                    OpenAiStreamingFailureException terminalFailure =
                            assertInstanceOf(OpenAiStreamingFailureException.class, failure);
                    assertSame(upstreamFailure, terminalFailure.upstreamFailure());
                })
                .verify();
        verify(responseService).createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class));
        verify(rateLimitService).tryReserveRequest(RateLimitService.ApiProvider.OPENAI);
    }

    @Test
    void streamingFailureAfterTextIsTerminal() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiRequestFactory requestFactory = testRequestFactory();
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, requestFactory, providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        StreamResponse<ResponseStreamEvent> providerStream = mock();
        ResponseStreamEvent visibleTextEvent = mock(ResponseStreamEvent.class);
        ResponseStreamEvent failedStreamEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent visibleTextDelta = mock(ResponseTextDeltaEvent.class);
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(asyncProviderStream(providerStream));
        when(visibleTextEvent.outputTextDelta()).thenReturn(Optional.of(visibleTextDelta));
        when(visibleTextDelta.delta()).thenReturn("first token");
        when(failedStreamEvent.outputTextDelta()).thenThrow(upstreamFailure);
        when(providerStream.stream()).thenAnswer(ignoredInvocation -> Stream.of(visibleTextEvent, failedStreamEvent));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

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
    void completionPreservesAsyncTransportFailure() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        CompletableFuture<Response> providerCompletionFuture = new CompletableFuture<>();
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
        when(responseService.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(providerCompletionFuture);
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);

        StepVerifier.create(streamingService.complete("prompt", 0.7))
                .then(() -> providerCompletionFuture.completeExceptionally(upstreamFailure))
                .expectErrorSatisfies(completionFailure -> assertSame(upstreamFailure, completionFailure))
                .verify();

        verify(openAiClient, never()).responses();
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
        return new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.4", configuredLlmProperties());
    }

    private static OpenAiProviderRoutingService configuredProviderRoutingService(RateLimitService rateLimitService) {
        return new OpenAiProviderRoutingService(rateLimitService, configuredLlmProperties());
    }

    private static OpenAIStreamingService streamingServiceForProviderStream(
            RateLimitService rateLimitService, StreamResponse<ResponseStreamEvent> providerStream) {
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService providerRoutingService = configuredProviderRoutingService(rateLimitService);
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                rateLimitService, testRequestFactory(), providerRoutingService, new OpenAiStreamingFailureReporter());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ResponseServiceAsync responseService = mockAsyncResponseService(openAiClient);
        when(responseService.createStreaming(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenReturn(asyncProviderStream(providerStream));
        ReflectionTestUtils.setField(streamingService, "openAiClient", openAiClient);
        return streamingService;
    }

    private static ResponseServiceAsync mockAsyncResponseService(OpenAIClient client) {
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responseService = mock(ResponseServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.responses()).thenReturn(responseService);
        return responseService;
    }

    private static AsyncStreamResponse<ResponseStreamEvent> asyncProviderStream(
            StreamResponse<ResponseStreamEvent> blockingProviderStream) {
        return new AsyncStreamResponse<>() {
            private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

            @Override
            public AsyncStreamResponse<ResponseStreamEvent> subscribe(
                    AsyncStreamResponse.Handler<? super ResponseStreamEvent> responseEventHandler) {
                try (Stream<ResponseStreamEvent> responseEvents = blockingProviderStream.stream()) {
                    responseEvents.forEach(responseEventHandler::onNext);
                    responseEventHandler.onComplete(Optional.empty());
                    completionFuture.complete(null);
                } catch (Throwable streamingFailure) {
                    responseEventHandler.onComplete(Optional.of(streamingFailure));
                    completionFuture.completeExceptionally(streamingFailure);
                }
                return this;
            }

            @Override
            public AsyncStreamResponse<ResponseStreamEvent> subscribe(
                    AsyncStreamResponse.Handler<? super ResponseStreamEvent> responseEventHandler,
                    Executor callbackExecutor) {
                callbackExecutor.execute(() -> subscribe(responseEventHandler));
                return this;
            }

            @Override
            public CompletableFuture<Void> onCompleteFuture() {
                return completionFuture;
            }

            @Override
            public void close() {
                blockingProviderStream.close();
            }
        };
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
}
