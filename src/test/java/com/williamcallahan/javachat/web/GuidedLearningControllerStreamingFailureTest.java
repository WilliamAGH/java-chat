package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_CITATION;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PREPARING;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_FATAL_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ConfiguredProviderTemporarilyUnavailableException;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies guided stream boundaries keep failure diagnostics internal without duplicating terminal alerts. */
@JsonTest
class GuidedLearningControllerStreamingFailureTest {
    private static final String SESSION_ID = "guided-session";
    private static final String LESSON_SLUG = "sealed-classes";
    private static final String USER_QUERY = "explain permits";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(GuidedLearningController.class);
    private ExpectedLogEvents controllerLogEvents;

    @Autowired
    ObjectMapper objectMapper;

    private GuidedLearningService guidedLearningService;
    private ChatMemoryService chatMemoryService;
    private OpenAIStreamingService streamingService;
    private GuidedLearningController guidedController;

    @BeforeEach
    void setUpController() {
        controllerLogEvents = ExpectedLogEvents.capture(controllerLogger);
        guidedLearningService = mock(GuidedLearningService.class);
        chatMemoryService = mock(ChatMemoryService.class);
        streamingService = mock(OpenAIStreamingService.class);
        guidedController = new GuidedLearningController(
                guidedLearningService,
                chatMemoryService,
                streamingService,
                new ExceptionResponseBuilder(),
                mock(MarkdownService.class),
                new SseSupport(objectMapper),
                new AppProperties());
        when(guidedLearningService.getLesson(LESSON_SLUG)).thenReturn(Optional.of(listedLesson()));
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogEvents.close();
    }

    @Test
    void guidedChatKeepsTerminalExceptionTypeOutOfRetryableClientError() throws JsonProcessingException {
        ReportedTerminalStreamingFailure terminalFailure = terminalFailure();
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationOutcomeForContextDocuments(anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(
                        new StreamingResult(Flux.error(terminalFailure), RateLimitService.ApiProvider.OPENAI)));
        when(streamingService.isRecoverableStreamingFailure(terminalFailure)).thenReturn(true);

        List<ServerSentEvent<String>> streamEvents = guidedController.stream(
                        new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        String serializedStreamError = serializedErrorEvent(streamEvents);
        SseSupport.SseEventPayload streamError =
                objectMapper.readValue(serializedStreamError, SseSupport.SseEventPayload.class);
        assertEquals("Streaming error", streamError.message());
        assertEquals("The response stream encountered an error. Please try again.", streamError.details());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, streamError.code());
        assertEquals(Boolean.TRUE, streamError.retryable());
        assertEquals(STATUS_STAGE_STREAM, streamError.stage());
        assertFalse(serializedStreamError.contains(IllegalStateException.class.getSimpleName()));
        assertFalse(streamEvents.stream().anyMatch(streamEvent -> EVENT_CITATION.equals(streamEvent.event())));
        verify(chatMemoryService, never()).addExchange(eq(SESSION_ID), eq(USER_QUERY), any());
        assertEquals(0, controllerErrorCount());
    }

    @Test
    void configuredProviderCooldownUsesTheSharedRetryableClientError() throws JsonProcessingException {
        ConfiguredProviderTemporarilyUnavailableException configuredProviderFailure =
                new ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider.GITHUB_MODELS);
        ReportedTerminalStreamingFailure terminalFailure =
                new ReportedTerminalStreamingFailure(configuredProviderFailure);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationOutcomeForContextDocuments(anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(
                        new StreamingResult(Flux.error(terminalFailure), RateLimitService.ApiProvider.OPENAI)));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                guidedController.stream(
                                new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY),
                                new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "guided stream events");

        String serializedStreamError = serializedErrorEvent(streamEvents);
        SseSupport.SseEventPayload streamError =
                objectMapper.readValue(serializedStreamError, SseSupport.SseEventPayload.class);
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_MESSAGE, streamError.message());
        assertEquals(SseSupport.CONFIGURED_PROVIDER_UNAVAILABLE_DETAILS, streamError.details());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, streamError.code());
        assertEquals(Boolean.TRUE, streamError.retryable());
        assertEquals(STATUS_STAGE_STREAM, streamError.stage());
        assertFalse(serializedStreamError.contains(
                ConfiguredProviderTemporarilyUnavailableException.class.getSimpleName()));
        assertFalse(serializedStreamError.contains(RateLimitService.ApiProvider.GITHUB_MODELS.getName()));
        assertEquals(0, controllerErrorCount());
    }

    @Test
    void guidedLessonContentDoesNotEmitDuplicateErrorForTerminalStreamingFailure() {
        ReportedTerminalStreamingFailure terminalFailure = terminalFailure();
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(LESSON_SLUG);
        listedLesson.setTitle("Sealed Classes");
        when(guidedLearningService.getLesson(LESSON_SLUG)).thenReturn(Optional.of(listedLesson));
        when(guidedLearningService.streamLessonContent(LESSON_SLUG)).thenReturn(Flux.error(terminalFailure));
        when(streamingService.isRecoverableStreamingFailure(terminalFailure)).thenReturn(true);

        List<ServerSentEvent<String>> streamEvents = guidedController
                .streamLesson(LESSON_SLUG, new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        assertEquals(0, controllerErrorCount());
    }

    @Test
    void unavailableProviderEmitsPreparationStatusBeforeTheFatalConfigurationError() throws JsonProcessingException {
        when(streamingService.isAvailable()).thenReturn(false);
        MockHttpServletResponse streamingResponse = new MockHttpServletResponse();

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                guidedController.stream(new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), streamingResponse)
                        .collectList()
                        .block(),
                "guided stream events");

        assertEquals(2, streamEvents.size());
        assertEquals(EVENT_STATUS, streamEvents.getFirst().event());
        SseSupport.SseEventPayload preparationStatus = objectMapper.readValue(
                Objects.requireNonNull(streamEvents.getFirst().data(), "preparation status data"),
                SseSupport.SseEventPayload.class);
        assertEquals(STATUS_CODE_STREAM_PREPARING, preparationStatus.code());
        assertEquals(STATUS_STAGE_RETRIEVAL, preparationStatus.stage());

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
        assertEquals("no", streamingResponse.getHeader("X-Accel-Buffering"));
        assertEquals("no-cache, no-transform", streamingResponse.getHeader("Cache-Control"));
        verify(chatMemoryService, never()).getHistory(SESSION_ID);
        verify(guidedLearningService, never())
                .buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString());
    }

    @Test
    void guidedChatKeepsNonTerminalExceptionTypeInStructuredLogOnly() throws JsonProcessingException {
        IllegalStateException upstreamFailure = new IllegalStateException(UPSTREAM_SECRET_MESSAGE);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationOutcomeForContextDocuments(anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(
                        new StreamingResult(Flux.error(upstreamFailure), RateLimitService.ApiProvider.OPENAI)));
        when(streamingService.isRecoverableStreamingFailure(upstreamFailure)).thenReturn(false);

        List<ServerSentEvent<String>> streamEvents = guidedController.stream(
                        new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        String serializedStreamError = serializedErrorEvent(streamEvents);
        SseSupport.SseEventPayload streamError =
                objectMapper.readValue(serializedStreamError, SseSupport.SseEventPayload.class);
        assertEquals("Streaming error", streamError.message());
        assertEquals(STATUS_CODE_STREAM_PROVIDER_FATAL_ERROR, streamError.code());
        assertEquals(Boolean.FALSE, streamError.retryable());
        assertFalse(serializedStreamError.contains(IllegalStateException.class.getSimpleName()));
        assertFalse(serializedStreamError.contains(UPSTREAM_SECRET_MESSAGE));
        ILoggingEvent controllerAlert = controllerLogEvents.events().stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow();
        assertLogField(controllerAlert, "sessionId", SESSION_ID);
        assertLogField(controllerAlert, "lessonSlug", LESSON_SLUG);
        assertLogField(controllerAlert, "exceptionType", IllegalStateException.class.getSimpleName());
        assertNull(controllerAlert.getThrowableProxy());
        assertFalse(controllerAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
    }

    private static ReportedTerminalStreamingFailure terminalFailure() {
        return new ReportedTerminalStreamingFailure(new IllegalStateException("upstream failure"));
    }

    private static GuidedLesson listedLesson() {
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(LESSON_SLUG);
        listedLesson.setTitle("Sealed Classes");
        return listedLesson;
    }

    private long controllerErrorCount() {
        return controllerLogEvents.events().stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .count();
    }

    private static String serializedErrorEvent(List<ServerSentEvent<String>> streamEvents) {
        ServerSentEvent<String> errorEvent = streamEvents.stream()
                .filter(streamEvent -> EVENT_ERROR.equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        return Objects.requireNonNull(errorEvent.data(), "error event data");
    }

    private void assertLogField(ILoggingEvent controllerAlert, String fieldName, Object expectedField) {
        assertTrue(controllerAlert.getKeyValuePairs().stream()
                .anyMatch(structuredField ->
                        structuredField.key.equals(fieldName) && structuredField.value.equals(expectedField)));
    }
}
