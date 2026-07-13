package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.InternalServerException;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.OpenAiStreamingFailureException;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies guided stream boundaries do not duplicate service-owned terminal stream alerts. */
class GuidedLearningControllerStreamingFailureTest {
    private static final String SESSION_ID = "guided-session";
    private static final String LESSON_SLUG = "sealed-classes";
    private static final String USER_QUERY = "explain permits";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(GuidedLearningController.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private GuidedLearningService guidedLearningService;
    private ChatMemoryService chatMemoryService;
    private OpenAIStreamingService streamingService;
    private GuidedLearningController guidedController;

    @BeforeEach
    void setUpController() {
        logAppender.start();
        controllerLogger.addAppender(logAppender);
        guidedLearningService = mock(GuidedLearningService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        chatMemoryService = mock(ChatMemoryService.class);
        streamingService = mock(OpenAIStreamingService.class);
        guidedController = new GuidedLearningController(
                guidedLearningService,
                retrievalService,
                chatMemoryService,
                streamingService,
                new ExceptionResponseBuilder(),
                mock(MarkdownService.class),
                new SseSupport(new ObjectMapper()),
                new AppProperties());
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogger.detachAppender(logAppender);
        logAppender.stop();
        logAppender.list.clear();
    }

    @Test
    void guidedChatDoesNotEmitDuplicateErrorForTerminalStreamingFailure() {
        OpenAiStreamingFailureException terminalFailure = terminalFailure();
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationsForBookDocuments(anyList())).thenReturn(List.of());
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.error(terminalFailure), RateLimitService.ApiProvider.OPENAI, Flux.empty())));
        when(streamingService.isRecoverableStreamingFailure(terminalFailure)).thenReturn(true);

        List<?> streamEvents = guidedController.stream(
                        new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        assertEquals(0, controllerErrorCount());
    }

    @Test
    void guidedLessonContentDoesNotEmitDuplicateErrorForTerminalStreamingFailure() {
        OpenAiStreamingFailureException terminalFailure = terminalFailure();
        when(guidedLearningService.getCachedLessonMarkdown(LESSON_SLUG)).thenReturn(Optional.empty());
        when(guidedLearningService.streamLessonContent(LESSON_SLUG)).thenReturn(Flux.error(terminalFailure));
        when(streamingService.isRecoverableStreamingFailure(terminalFailure)).thenReturn(true);

        List<?> streamEvents = guidedController
                .streamLesson(LESSON_SLUG, new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        assertEquals(0, controllerErrorCount());
    }

    @Test
    void guidedChatLogsBoundedNonTerminalContextWithoutThrowable() {
        IllegalStateException upstreamFailure = new IllegalStateException(UPSTREAM_SECRET_MESSAGE);
        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationsForBookDocuments(anyList())).thenReturn(List.of());
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.error(upstreamFailure), RateLimitService.ApiProvider.OPENAI, Flux.empty())));
        when(streamingService.isRecoverableStreamingFailure(upstreamFailure)).thenReturn(false);

        List<?> streamEvents = guidedController.stream(
                        new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertFalse(streamEvents.isEmpty());
        ILoggingEvent controllerAlert = logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow();
        assertLogField(controllerAlert, "sessionId", SESSION_ID);
        assertLogField(controllerAlert, "lessonSlug", LESSON_SLUG);
        assertLogField(controllerAlert, "exceptionType", IllegalStateException.class.getSimpleName());
        assertNull(controllerAlert.getThrowableProxy());
        assertFalse(controllerAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
    }

    private static OpenAiStreamingFailureException terminalFailure() {
        OpenAiStreamingFailureException terminalFailure = mock(OpenAiStreamingFailureException.class);
        when(terminalFailure.getCause()).thenReturn(mock(InternalServerException.class));
        return terminalFailure;
    }

    private long controllerErrorCount() {
        return logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .count();
    }

    private void assertLogField(ILoggingEvent controllerAlert, String fieldName, Object expectedField) {
        assertTrue(controllerAlert.getKeyValuePairs().stream()
                .anyMatch(structuredField ->
                        structuredField.key.equals(fieldName) && structuredField.value.equals(expectedField)));
    }
}
