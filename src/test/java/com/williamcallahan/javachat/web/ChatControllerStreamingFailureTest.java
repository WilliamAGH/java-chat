package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_CITATION;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_TEXT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_RETRIEVAL_TIMEOUT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PREPARING;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static com.williamcallahan.javachat.web.SseConstants.STREAM_CHUNK_COALESCE_MAX_ITEMS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.InternalServerException;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ReactorHooksConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.ConfiguredProviderTemporarilyUnavailableException;
import com.williamcallahan.javachat.service.HybridSearchPartialFailureException;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RerankingFailureException;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.test.StepVerifier;

/** Verifies the chat request boundary does not duplicate the service-owned terminal stream alert. */
@JsonTest
class ChatControllerStreamingFailureTest {
    private static final String SESSION_ID = "session\nid";
    private static final String USER_QUERY = "explain sealed classes";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";
    private static final int ASYNC_ASSERTION_TIMEOUT_SECONDS = 2;
    private static final int UNPROCESSABLE_ENTITY_STATUS = 422;

    private final Logger pipelineLogger = (Logger) LoggerFactory.getLogger("PIPELINE");
    private final Logger reactorHooksLogger = (Logger) LoggerFactory.getLogger(ReactorHooksConfig.class);
    private final Logger reactorOperatorsLogger = (Logger) LoggerFactory.getLogger(Operators.class);

    @Autowired
    ObjectMapper objectMapper;

    private ExpectedLogEvents pipelineLogEvents;
    private ExpectedLogEvents reactorHookLogEvents;
    private ExpectedLogEvents reactorOperatorLogEvents;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void capturePipelineLogs() {
        meterRegistry = new SimpleMeterRegistry();
        pipelineLogEvents = ExpectedLogEvents.capture(pipelineLogger);
        reactorHookLogEvents = ExpectedLogEvents.capture(reactorHooksLogger);
        reactorOperatorLogEvents = ExpectedLogEvents.capture(reactorOperatorsLogger);
    }

    @AfterEach
    void stopCapturingPipelineLogs() {
        reactorOperatorLogEvents.close();
        reactorHookLogEvents.close();
        pipelineLogEvents.close();
    }

    @Test
    void terminalGatewayTimeoutDoesNotEmitDuplicateControllerError() {
        InternalServerException upstreamFailure = mock(InternalServerException.class);
        when(upstreamFailure.statusCode()).thenReturn(504);
        when(upstreamFailure.getMessage()).thenReturn(UPSTREAM_SECRET_MESSAGE);
        ReportedTerminalStreamingFailure terminalFailure = new ReportedTerminalStreamingFailure(upstreamFailure);
        List<ServerSentEvent<String>> streamEvents = streamFailure(terminalFailure, true);

        assertFalse(streamEvents.isEmpty());
        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        assertTrue(errorEvent.data().contains("InternalServerException [httpStatus=504]"));
        assertFalse(errorEvent.data().contains(UPSTREAM_SECRET_MESSAGE));
        assertEquals(
                0,
                pipelineLogEvents.events().stream()
                        .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                        .count());
    }

    @Test
    void nonExceptionDiagnosticsDoNotExposeFailureMessage() {
        AssertionError upstreamFailure = new AssertionError(UPSTREAM_SECRET_MESSAGE);

        List<ServerSentEvent<String>> streamEvents = streamFailure(upstreamFailure, false);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        assertTrue(errorEvent.data().contains(AssertionError.class.getName()));
        assertFalse(errorEvent.data().contains(UPSTREAM_SECRET_MESSAGE));
        ILoggingEvent controllerAlert = pipelineLogEvents.events().stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow();
        assertLogField(controllerAlert, "sessionId", "session?id");
        assertLogField(controllerAlert, "exceptionType", AssertionError.class.getSimpleName());
        assertNull(controllerAlert.getThrowableProxy());
        assertFalse(controllerAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
    }

    @Test
    void retrievalFailuresExplainThemselvesWithoutLeakingTheExceptionClassName() throws JsonProcessingException {
        HybridSearchPartialFailureException retrievalFailure = new HybridSearchPartialFailureException(
                "Qdrant retrieval failed for 4 collection(s)",
                List.of(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-chat-dev-docs",
                        "Timeout",
                        "Qdrant query exceeded timeout 5000ms",
                        HybridSearchPartialFailureException.FailureDisposition.TRANSIENT)));

        List<ServerSentEvent<String>> streamEvents = streamFailure(retrievalFailure, false);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        String serializedError = Objects.requireNonNull(errorEvent.data(), "error event data");
        SseSupport.SseEventPayload retrievalErrorEvent =
                objectMapper.readValue(serializedError, SseSupport.SseEventPayload.class);
        assertFalse(
                retrievalErrorEvent.message().contains(HybridSearchPartialFailureException.class.getSimpleName()),
                "user-facing message must not leak the exception class name");
        assertTrue(retrievalErrorEvent.message().contains("Java documentation"));
        assertFalse(serializedError.contains(HybridSearchPartialFailureException.class.getSimpleName()));
        assertFalse(serializedError.contains("java-chat-dev-docs"));
    }

    @Test
    void rerankingFailuresExplainThemselvesWithoutLeakingTheExceptionClassName() throws JsonProcessingException {
        RerankingFailureException rerankingFailure = new RerankingFailureException("Reranking service unavailable");

        List<ServerSentEvent<String>> streamEvents = streamFailure(rerankingFailure, false);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        String serializedError = Objects.requireNonNull(errorEvent.data(), "error event data");
        SseSupport.SseEventPayload rerankingErrorEvent =
                objectMapper.readValue(serializedError, SseSupport.SseEventPayload.class);
        assertFalse(
                rerankingErrorEvent.message().contains(RerankingFailureException.class.getSimpleName()),
                "user-facing message must not leak the exception class name");
        assertFalse(serializedError.contains(RerankingFailureException.class.getSimpleName()));
        assertFalse(serializedError.contains("Reranking service unavailable"));
    }

    @Test
    void unpreservableReasoningIntentNamesTheEffortAndTheOwningSetting() throws JsonProcessingException {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setReasoningEffort("xhigh");

        List<ServerSentEvent<String>> streamEvents =
                streamFailure(unprocessableEntityFailure("unpreservable_reasoning_intent"), false, appProperties);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        String serializedError = Objects.requireNonNull(errorEvent.data(), "error event data");
        SseSupport.SseEventPayload reasoningErrorEvent =
                objectMapper.readValue(serializedError, SseSupport.SseEventPayload.class);
        assertTrue(reasoningErrorEvent.message().contains("xhigh"));
        assertTrue(reasoningErrorEvent.message().contains("app.llm.reasoning-effort"));
        assertTrue(reasoningErrorEvent.message().contains("unpreservable_reasoning_intent"));
        assertEquals(Boolean.FALSE, reasoningErrorEvent.retryable());
    }

    @Test
    void otherUnprocessableEntityFailuresKeepTheGenericMessage() throws JsonProcessingException {
        List<ServerSentEvent<String>> streamEvents =
                streamFailure(unprocessableEntityFailure("invalid_request"), false);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        SseSupport.SseEventPayload genericErrorEvent = objectMapper.readValue(
                Objects.requireNonNull(errorEvent.data(), "error event data"), SseSupport.SseEventPayload.class);
        assertEquals(
                "Something went wrong while generating this response. Please try again.", genericErrorEvent.message());
        assertEquals(Boolean.FALSE, genericErrorEvent.retryable());
    }

    private static ReportedTerminalStreamingFailure unprocessableEntityFailure(String gatewayErrorCode) {
        InternalServerException gatewayFailure = mock(InternalServerException.class);
        when(gatewayFailure.statusCode()).thenReturn(UNPROCESSABLE_ENTITY_STATUS);
        when(gatewayFailure.code()).thenReturn(Optional.of(gatewayErrorCode));
        return new ReportedTerminalStreamingFailure(gatewayFailure);
    }

    @Test
    void configuredProviderCooldownEmitsStableRetryableClientError() throws JsonProcessingException {
        ConfiguredProviderTemporarilyUnavailableException configuredProviderFailure =
                new ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider.OPENAI);

        List<ServerSentEvent<String>> streamEvents = streamFailure(configuredProviderFailure, false);

        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> "error".equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        String serializedError = Objects.requireNonNull(errorEvent.data(), "error event data");
        SseSupport.SseEventPayload providerCooldownEvent =
                objectMapper.readValue(serializedError, SseSupport.SseEventPayload.class);
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_MESSAGE, providerCooldownEvent.message());
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_DETAILS, providerCooldownEvent.details());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, providerCooldownEvent.code());
        assertEquals(Boolean.TRUE, providerCooldownEvent.retryable());
        assertEquals(STATUS_STAGE_STREAM, providerCooldownEvent.stage());
        assertFalse(serializedError.contains(ConfiguredProviderTemporarilyUnavailableException.class.getSimpleName()));
        assertFalse(serializedError.contains(RateLimitService.ApiProvider.OPENAI.getName()));
    }

    @Test
    void wrappedQdrantDeadlineUsesTheRetrievalTimeoutContract() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        TimeoutException qdrantDeadlineFailure = new TimeoutException("Qdrant query deadline");
        qdrantDeadlineFailure.initCause(Status.DEADLINE_EXCEEDED.asRuntimeException());
        HybridSearchPartialFailureException.CollectionSearchFailure collectionSearchFailure =
                new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-docs",
                        "Timeout",
                        UPSTREAM_SECRET_MESSAGE,
                        HybridSearchPartialFailureException.FailureDisposition.TRANSIENT);
        HybridSearchPartialFailureException hybridDeadlineFailure = new HybridSearchPartialFailureException(
                "Qdrant retrieval failed", List.of(collectionSearchFailure), List.of(qdrantDeadlineFailure));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenThrow(hybridDeadlineFailure);

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        assertEquals(2, streamEvents.size());
        assertEquals(EVENT_STATUS, streamEvents.getFirst().event());
        ServerSentEvent<String> terminalErrorEvent = streamEvents.getLast();
        assertEquals(EVENT_ERROR, terminalErrorEvent.event());
        String serializedTimeoutError =
                Objects.requireNonNull(terminalErrorEvent.data(), "retrieval timeout error data");
        SseSupport.SseEventPayload timeoutError =
                objectMapper.readValue(serializedTimeoutError, SseSupport.SseEventPayload.class);
        assertEquals("Response preparation timed out", timeoutError.message());
        assertEquals(STATUS_CODE_RETRIEVAL_TIMEOUT, timeoutError.code());
        assertEquals(Boolean.TRUE, timeoutError.retryable());
        assertEquals(STATUS_STAGE_RETRIEVAL, timeoutError.stage());
        assertFalse(serializedTimeoutError.contains(UPSTREAM_SECRET_MESSAGE));
        verify(streamingService, never()).streamResponse(any(StructuredPrompt.class), anyDouble());
        List<ILoggingEvent> retrievalTimeoutWarnings = pipelineLogEvents.events().stream()
                .filter(logEvent -> logEvent.getLevel() == Level.WARN
                        && "Response preparation timeout".equals(logEvent.getFormattedMessage()))
                .toList();
        assertEquals(1, retrievalTimeoutWarnings.size());
        ILoggingEvent retrievalTimeoutWarning = retrievalTimeoutWarnings.getFirst();
        assertLogFieldPresent(retrievalTimeoutWarning, "requestToken");
        assertLogField(retrievalTimeoutWarning, "sessionId", "session?id");
        assertLogField(retrievalTimeoutWarning, "code", STATUS_CODE_RETRIEVAL_TIMEOUT);
        assertLogField(retrievalTimeoutWarning, "stage", STATUS_STAGE_RETRIEVAL);
        assertLogField(
                retrievalTimeoutWarning, "exceptionType", HybridSearchPartialFailureException.class.getSimpleName());
        assertNull(retrievalTimeoutWarning.getThrowableProxy());
        assertFalse(retrievalTimeoutWarning.toString().contains(UPSTREAM_SECRET_MESSAGE));
        assertEquals(
                1.0,
                meterRegistry
                        .get(SseSupport.RETRIEVAL_TIMEOUT_COUNTER_NAME)
                        .counter()
                        .count());
        assertEquals(
                0,
                pipelineLogEvents.events().stream()
                        .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                        .count());
    }

    @Test
    void streamEmitsPreparationStatusBeforeDeferredRetrievalAndProviderWork() {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());

        StepVerifier.create(
                        chatController.stream(
                                new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse()),
                        1)
                .assertNext(streamEvent -> {
                    assertEquals(EVENT_STATUS, streamEvent.event());
                    assertDoesNotThrow(() -> {
                        SseSupport.SseEventPayload preparationStatus = objectMapper.readValue(
                                Objects.requireNonNull(streamEvent.data(), "preparation status event data"),
                                SseSupport.SseEventPayload.class);
                        assertEquals(STATUS_CODE_STREAM_PREPARING, preparationStatus.code());
                        assertEquals(STATUS_STAGE_RETRIEVAL, preparationStatus.stage());
                    });
                })
                .thenCancel()
                .verify();

        verifyNoInteractions(chatMemoryService, chatService, streamingService, retrievalService);
    }

    @Test
    void streamSubscriptionReturnsAfterPreparationStatusWhileRetrievalIsBlocked()
            throws JsonProcessingException, InterruptedException, ExecutionException, TimeoutException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        CountDownLatch retrievalStarted = new CountDownLatch(1);
        CountDownLatch releaseRetrieval = new CountDownLatch(1);
        CountDownLatch retrievalFinished = new CountDownLatch(1);
        CountDownLatch preparationStatusObserved = new CountDownLatch(1);
        AtomicReference<ServerSentEvent<String>> firstStreamEvent = new AtomicReference<>();
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        AtomicReference<Disposable> activeSubscription = new AtomicReference<>();

        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenAnswer(ignoredInvocation -> {
            retrievalStarted.countDown();
            try {
                assertTrue(
                        releaseRetrieval.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "test should release blocked retrieval");
                return List.of();
            } finally {
                retrievalFinished.countDown();
            }
        });
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.never());

        ExecutorService subscriptionExecutor = Executors.newSingleThreadExecutor();
        Future<?> subscriptionRegistration = subscriptionExecutor.submit(() -> activeSubscription.set(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .subscribe(
                                streamEvent -> {
                                    if (firstStreamEvent.compareAndSet(null, streamEvent)) {
                                        preparationStatusObserved.countDown();
                                    }
                                },
                                streamFailure::set)));

        try {
            assertTrue(
                    preparationStatusObserved.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "preparation status should be observable before retrieval completes");
            assertTrue(
                    retrievalStarted.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "retrieval should start after the preparation status");
            subscriptionRegistration.get(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ServerSentEvent<String> preparationEvent =
                    Objects.requireNonNull(firstStreamEvent.get(), "first stream event");
            assertEquals(EVENT_STATUS, preparationEvent.event());
            SseSupport.SseEventPayload preparationStatus = objectMapper.readValue(
                    Objects.requireNonNull(preparationEvent.data(), "preparation status event data"),
                    SseSupport.SseEventPayload.class);
            assertEquals(STATUS_CODE_STREAM_PREPARING, preparationStatus.code());
            assertEquals(STATUS_STAGE_RETRIEVAL, preparationStatus.stage());
            assertEquals(1L, releaseRetrieval.getCount(), "subscription should return while retrieval remains blocked");
            assertNull(streamFailure.get());
        } finally {
            releaseRetrieval.countDown();
            assertTrue(
                    retrievalFinished.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "blocked retrieval should finish before stream cancellation");
            Disposable streamSubscription = activeSubscription.get();
            if (streamSubscription != null) {
                streamSubscription.dispose();
            }
            subscriptionRegistration.cancel(true);
            subscriptionExecutor.shutdownNow();
            assertTrue(
                    subscriptionExecutor.awaitTermination(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "subscription executor should terminate after cancellation");
        }
    }

    @Test
    void disposingStreamInterruptsBlockedPreparationAndPreventsDownstreamWork() throws InterruptedException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        CountDownLatch retrievalStarted = new CountDownLatch(1);
        CountDownLatch releaseRetrieval = new CountDownLatch(1);
        CountDownLatch retrievalInterrupted = new CountDownLatch(1);
        CountDownLatch retrievalFinished = new CountDownLatch(1);
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();

        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenAnswer(ignoredInvocation -> {
            retrievalStarted.countDown();
            try {
                releaseRetrieval.await();
                return List.of();
            } catch (InterruptedException interruptedFailure) {
                retrievalInterrupted.countDown();
                throw interruptedFailure;
            } finally {
                retrievalFinished.countDown();
            }
        });

        Disposable streamSubscription = chatController.stream(
                        new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                .subscribe(
                        preparationEvent -> assertEquals(EVENT_STATUS, preparationEvent.event()), streamFailure::set);

        try {
            assertTrue(
                    retrievalStarted.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "retrieval should be blocked before cancellation");

            streamSubscription.dispose();

            assertTrue(
                    retrievalInterrupted.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "disposing the stream should interrupt blocked retrieval");
            assertTrue(
                    retrievalFinished.await(ASYNC_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "blocked retrieval should terminate after cancellation");
            assertNull(streamFailure.get());
            verifyNoInteractions(chatService, retrievalService);
            verify(streamingService, never()).streamResponse(any(StructuredPrompt.class), anyDouble());
            verify(chatMemoryService, never()).addExchange(eq(SESSION_ID), eq(USER_QUERY), any());
        } finally {
            releaseRetrieval.countDown();
            streamSubscription.dispose();
        }
    }

    @Test
    void providerCooldownEmitsPreparationStatusBeforeTheRetryableAdmissionError() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        when(streamingService.isAvailable()).thenReturn(true);
        when(streamingService.canAttemptRequest()).thenReturn(false);

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        assertEquals(2, streamEvents.size());
        assertEquals(EVENT_STATUS, streamEvents.getFirst().event());
        ServerSentEvent<String> terminalErrorEvent = streamEvents.getLast();
        assertEquals(EVENT_ERROR, terminalErrorEvent.event());
        SseSupport.SseEventPayload terminalError = objectMapper.readValue(
                Objects.requireNonNull(terminalErrorEvent.data(), "terminal error data"),
                SseSupport.SseEventPayload.class);
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_MESSAGE, terminalError.message());
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_DETAILS, terminalError.details());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, terminalError.code());
        assertEquals(Boolean.TRUE, terminalError.retryable());
        assertEquals(STATUS_STAGE_STREAM, terminalError.stage());
        verify(chatMemoryService, never()).getHistory(SESSION_ID);
        verifyNoInteractions(chatService, retrievalService);
    }

    @Test
    void chatStreamCitesTheExactDocumentsUsedForPromptContextWithoutSecondDiscovery() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        Document officialPromptDocument = mock(Document.class);
        when(officialPromptDocument.getId()).thenReturn("official-prompt-document");
        Document truncatedPromptDocument = mock(Document.class);
        when(truncatedPromptDocument.getId()).thenReturn("truncated-prompt-document");
        Citation officialCitation = new Citation(
                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html",
                "List",
                "of()",
                "Creates an unmodifiable list.");
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        ChatService.StructuredPromptOutcome promptOutcome = new ChatService.StructuredPromptOutcome(
                StructuredPrompt.fromRawPrompt("test", 1),
                List.of(),
                List.of(officialPromptDocument, truncatedPromptDocument));
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenReturn(promptOutcome);
        when(chatService.citationOutcomeForRetainedContext(
                        USER_QUERY, promptOutcome, List.of(officialPromptDocument.getId())))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(officialCitation), 0));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI, List.of("official-prompt-document"))));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        assertEquals(EVENT_STATUS, streamEvents.getFirst().event());
        ServerSentEvent<String> citationEvent = streamEvents.stream()
                .filter(streamEvent -> EVENT_CITATION.equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        List<Citation> streamedCitations = Arrays.asList(objectMapper.readValue(
                Objects.requireNonNull(citationEvent.data(), "citation event data"), Citation[].class));

        assertEquals(1, streamedCitations.size());
        assertEquals(officialCitation.getUrl(), streamedCitations.getFirst().getUrl());
        verify(chatService)
                .citationOutcomeForRetainedContext(USER_QUERY, promptOutcome, List.of(officialPromptDocument.getId()));
        verifyNoInteractions(retrievalService);
        verify(chatService, never()).citationsFor(anyString());
    }

    @Test
    void chatStreamEmitsExactOverloadSelectionFromPromptContext() throws JsonProcessingException {
        String exactOverloadQuery = "Explain java.util.List.of(E,E)";
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        Document broadPromptDocument = mock(Document.class);
        when(broadPromptDocument.getId()).thenReturn("broad-prompt-document");
        Citation exactOverloadCitation = new Citation(
                "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html#of(E,E)",
                "List",
                "of(E,E)",
                "Creates an unmodifiable list containing two elements.");
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        ChatService.StructuredPromptOutcome promptOutcome = new ChatService.StructuredPromptOutcome(
                StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of(broadPromptDocument));
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(exactOverloadQuery), any(), anyLong()))
                .thenReturn(promptOutcome);
        when(chatService.citationOutcomeForRetainedContext(
                        exactOverloadQuery, promptOutcome, List.of(broadPromptDocument.getId())))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(exactOverloadCitation), 1));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI, List.of("broad-prompt-document"))));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(
                                new ChatStreamRequest(SESSION_ID, exactOverloadQuery), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        ServerSentEvent<String> citationEvent = streamEvents.stream()
                .filter(streamEvent -> EVENT_CITATION.equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        List<Citation> streamedCitations = Arrays.asList(objectMapper.readValue(
                Objects.requireNonNull(citationEvent.data(), "citation event data"), Citation[].class));
        long partialFailureStatusCount = streamEvents.stream()
                .filter(streamEvent -> EVENT_STATUS.equals(streamEvent.event()))
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .map(statusJson -> {
                    try {
                        return objectMapper.readValue(statusJson, SseSupport.SseEventPayload.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .filter(statusPayload -> "citation.partial-failure".equals(statusPayload.code()))
                .count();

        assertEquals(1, streamedCitations.size());
        assertEquals(
                exactOverloadCitation.getUrl(), streamedCitations.getFirst().getUrl());
        assertEquals("of(E,E)", streamedCitations.getFirst().getAnchor());
        assertEquals(1, partialFailureStatusCount);
        verify(chatService)
                .citationOutcomeForRetainedContext(
                        exactOverloadQuery, promptOutcome, List.of(broadPromptDocument.getId()));
        verifyNoInteractions(retrievalService);
        verify(chatService, never()).citationsFor(anyString());
    }

    @Test
    void chatStreamSurfacesNonzeroCitationFailuresBeforeCitationEvent() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatService.citationOutcomeForRetainedContext(
                        eq(USER_QUERY), any(ChatService.StructuredPromptOutcome.class), anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(
                        List.of(new Citation("https://example.com", "Example", "", "")), 2));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(
                        new StreamingResult(Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI, List.of())));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        int citationPartialFailureIndex = -1;
        int citationEventIndex = -1;
        for (int eventIndex = 0; eventIndex < streamEvents.size(); eventIndex++) {
            ServerSentEvent<String> streamEvent = streamEvents.get(eventIndex);
            if (EVENT_CITATION.equals(streamEvent.event())) {
                citationEventIndex = eventIndex;
                continue;
            }
            if (!EVENT_STATUS.equals(streamEvent.event())) {
                continue;
            }
            SseSupport.SseEventPayload chatStatus = objectMapper.readValue(
                    Objects.requireNonNull(streamEvent.data(), "chat status data"), SseSupport.SseEventPayload.class);
            if ("citation.partial-failure".equals(chatStatus.code())) {
                citationPartialFailureIndex = eventIndex;
                assertEquals(Boolean.FALSE, chatStatus.retryable());
                assertEquals("citation", chatStatus.stage());
            }
        }

        assertTrue(citationPartialFailureIndex >= 0, "chat stream should surface partial citation failure");
        assertTrue(citationEventIndex > citationPartialFailureIndex, "citation warning should precede citations");
    }

    @Test
    void streamBufferOverflowDoesNotPersistPartialChatAnswer() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                new AppProperties());
        Throwable streamBufferOverflowFailure = Exceptions.failWithOverflow();
        Flux<String> partialAnswerThenOverflow = Flux.range(0, STREAM_CHUNK_COALESCE_MAX_ITEMS)
                .map(chunkIndex -> "partial answer " + chunkIndex)
                .concatWith(Flux.error(streamBufferOverflowFailure));

        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatService.citationOutcomeForRetainedContext(
                        eq(USER_QUERY), any(ChatService.StructuredPromptOutcome.class), anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        partialAnswerThenOverflow, RateLimitService.ApiProvider.OPENAI, List.of())));
        when(streamingService.isRecoverableStreamingFailure(streamBufferOverflowFailure))
                .thenReturn(true);

        List<ServerSentEvent<String>> streamEvents = chatController.stream(
                        new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertTrue(streamEvents.stream().anyMatch(streamEvent -> EVENT_TEXT.equals(streamEvent.event())));
        ServerSentEvent<String> terminalErrorEvent = streamEvents.getLast();
        assertEquals(EVENT_ERROR, terminalErrorEvent.event());
        SseSupport.SseEventPayload terminalError = objectMapper.readValue(
                Objects.requireNonNull(terminalErrorEvent.data(), "terminal error event data"),
                SseSupport.SseEventPayload.class);
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, terminalError.code());
        assertEquals(Boolean.TRUE, terminalError.retryable());
        assertEquals(STATUS_STAGE_STREAM, terminalError.stage());
        assertFalse(streamEvents.stream().anyMatch(streamEvent -> EVENT_CITATION.equals(streamEvent.event())));
        verify(chatMemoryService, never()).addExchange(eq(SESSION_ID), eq(USER_QUERY), any());
    }

    private List<ServerSentEvent<String>> streamFailure(Throwable streamingFailure, boolean retryable) {
        return streamFailure(streamingFailure, retryable, new AppProperties());
    }

    private List<ServerSentEvent<String>> streamFailure(
            Throwable streamingFailure, boolean retryable, AppProperties appProperties) {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                createSseSupport(),
                new ExceptionResponseBuilder(),
                appProperties);

        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(anyList(), eq(USER_QUERY), any(), anyLong()))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.canAttemptRequest()).thenReturn(true);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatService.citationOutcomeForRetainedContext(
                        eq(USER_QUERY), any(ChatService.StructuredPromptOutcome.class), anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.error(streamingFailure), RateLimitService.ApiProvider.OPENAI, List.of())));
        when(streamingService.isRecoverableStreamingFailure(streamingFailure)).thenReturn(retryable);

        return chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();
    }

    private void assertLogField(ILoggingEvent controllerAlert, String fieldName, Object expectedField) {
        assertTrue(controllerAlert.getKeyValuePairs().stream()
                .anyMatch(structuredField ->
                        structuredField.key.equals(fieldName) && structuredField.value.equals(expectedField)));
    }

    private void assertLogFieldPresent(ILoggingEvent controllerAlert, String fieldName) {
        assertTrue(controllerAlert.getKeyValuePairs().stream()
                .anyMatch(structuredField -> structuredField.key.equals(fieldName)));
    }

    private SseSupport createSseSupport() {
        return new SseSupport(objectMapper, meterRegistry);
    }
}

/** Supplies a generic already-reported terminal failure to every web-boundary regression test. */
final class ReportedTerminalStreamingFailure extends RuntimeException implements ReportedStreamingFailure {

    ReportedTerminalStreamingFailure(Throwable upstreamFailure) {
        super("terminal streaming failure", Objects.requireNonNull(upstreamFailure, "upstreamFailure"));
    }

    /** Returns the provider failure that outer web boundaries should classify without re-reporting. */
    @Override
    public Throwable upstreamFailure() {
        return Objects.requireNonNull(getCause(), "upstreamFailure");
    }
}
