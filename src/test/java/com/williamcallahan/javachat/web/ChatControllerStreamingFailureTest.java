package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_CITATION;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_TEXT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PREPARING;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_FATAL_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.ReactorHooksConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.ConfiguredProviderTemporarilyUnavailableException;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
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

    private final Logger pipelineLogger = (Logger) LoggerFactory.getLogger("PIPELINE");
    private final Logger reactorHooksLogger = (Logger) LoggerFactory.getLogger(ReactorHooksConfig.class);
    private final Logger reactorOperatorsLogger = (Logger) LoggerFactory.getLogger(Operators.class);

    @Autowired
    ObjectMapper objectMapper;

    private ExpectedLogEvents pipelineLogEvents;
    private ExpectedLogEvents reactorHookLogEvents;
    private ExpectedLogEvents reactorOperatorLogEvents;

    @BeforeEach
    void capturePipelineLogs() {
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
    void configuredProviderCooldownEmitsStableRetryableClientError() throws JsonProcessingException {
        ConfiguredProviderTemporarilyUnavailableException configuredProviderFailure =
                new ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider.GITHUB_MODELS);

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
        assertFalse(serializedError.contains(RateLimitService.ApiProvider.GITHUB_MODELS.getName()));
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
    void unavailableProviderEmitsPreparationStatusBeforeTheFatalConfigurationError() throws JsonProcessingException {
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
        when(streamingService.isAvailable()).thenReturn(false);

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
        assertEquals(SseSupport.CONFIGURED_PROVIDER_CONFIGURATION_MESSAGE, terminalError.message());
        assertEquals(SseSupport.CONFIGURED_PROVIDER_CONFIGURATION_DETAILS, terminalError.details());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_FATAL_ERROR, terminalError.code());
        assertEquals(Boolean.FALSE, terminalError.retryable());
        assertEquals(STATUS_STAGE_STREAM, terminalError.stage());
        verify(chatMemoryService, never()).getHistory(SESSION_ID);
        verifyNoInteractions(chatService, retrievalService);
    }

    @Test
    void chatStreamSurfacesNonzeroCitationFailuresBeforeCitationEvent() throws JsonProcessingException {
        ChatService chatService = mock(ChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        SseStatusContractCatalog statusContractCatalog = createStatusContractCatalog();
        ChatController chatController = new ChatController(
                chatService,
                chatMemoryService,
                streamingService,
                retrievalService,
                new SseSupport(objectMapper, statusContractCatalog),
                new ExceptionResponseBuilder(),
                new AppProperties());
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.isAvailable()).thenReturn(true);
        when(retrievalService.toCitations(anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(
                        List.of(new Citation("https://example.com", "Example", "", "")), 2));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI)));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "chat stream events");

        SseStatusContractCatalog.SseStatusContract citationContract = statusContractCatalog.citationPartialFailure();
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
            if (citationContract.code().equals(chatStatus.code())) {
                citationPartialFailureIndex = eventIndex;
                assertEquals(Boolean.valueOf(citationContract.retryable()), chatStatus.retryable());
                assertEquals(citationContract.stage(), chatStatus.stage());
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
        Flux<String> partialAnswerThenOverflow = Flux.just("partial answer")
                .concatWith(Mono.delay(Duration.ofMillis(50)).thenMany(Flux.error(streamBufferOverflowFailure)));

        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.isAvailable()).thenReturn(true);
        when(retrievalService.toCitations(anyList())).thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(
                        Mono.just(new StreamingResult(partialAnswerThenOverflow, RateLimitService.ApiProvider.OPENAI)));
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
        verify(chatMemoryService, never()).addExchange(eq(SESSION_ID), eq(USER_QUERY), any());
    }

    private List<ServerSentEvent<String>> streamFailure(Throwable streamingFailure, boolean retryable) {
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
        when(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(streamingService.isAvailable()).thenReturn(true);
        when(retrievalService.toCitations(anyList())).thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(
                        new StreamingResult(Flux.error(streamingFailure), RateLimitService.ApiProvider.OPENAI)));
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

    private SseSupport createSseSupport() {
        return new SseSupport(objectMapper, createStatusContractCatalog());
    }

    private SseStatusContractCatalog createStatusContractCatalog() {
        return new SseStatusContractCatalog(objectMapper, new ClassPathResource("sse-status-contracts.json"));
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
