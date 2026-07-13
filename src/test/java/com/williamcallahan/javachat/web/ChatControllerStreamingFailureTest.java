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
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies the chat request boundary does not duplicate the service-owned terminal stream alert. */
class ChatControllerStreamingFailureTest {
    private static final String SESSION_ID = "session\nid";
    private static final String USER_QUERY = "explain sealed classes";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";

    private final Logger pipelineLogger = (Logger) LoggerFactory.getLogger("PIPELINE");
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void capturePipelineLogs() {
        logAppender.start();
        pipelineLogger.addAppender(logAppender);
    }

    @AfterEach
    void stopCapturingPipelineLogs() {
        pipelineLogger.detachAppender(logAppender);
        logAppender.stop();
        logAppender.list.clear();
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
                logAppender.list.stream()
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
        ILoggingEvent controllerAlert = logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow();
        assertLogField(controllerAlert, "sessionId", "session?id");
        assertLogField(controllerAlert, "exceptionType", AssertionError.class.getSimpleName());
        assertNull(controllerAlert.getThrowableProxy());
        assertFalse(controllerAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
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
                new SseSupport(new ObjectMapper()),
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
                .thenReturn(Mono.just(new StreamingResult(
                        Flux.error(streamingFailure), RateLimitService.ApiProvider.OPENAI, Flux.empty())));
        when(streamingService.isRecoverableStreamingFailure(streamingFailure)).thenReturn(retryable);

        return chatController.stream(new ChatStreamRequest(SESSION_ID, USER_QUERY, null), new MockHttpServletResponse())
                .collectList()
                .block();
    }

    private void assertLogField(ILoggingEvent controllerAlert, String fieldName, Object expectedField) {
        assertTrue(controllerAlert.getKeyValuePairs().stream()
                .anyMatch(structuredField ->
                        structuredField.key.equals(fieldName) && structuredField.value.equals(expectedField)));
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
