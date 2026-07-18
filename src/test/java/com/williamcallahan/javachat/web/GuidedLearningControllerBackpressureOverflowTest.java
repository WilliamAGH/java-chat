package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_TEXT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ReactorHooksConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

/** Verifies guided-chat persistence is skipped when SSE backpressure terminates a partial answer. */
@JsonTest
class GuidedLearningControllerBackpressureOverflowTest {
    private static final String SESSION_ID = "guided-session";
    private static final String LESSON_SLUG = "sealed-classes";
    private static final String USER_QUERY = "explain permits";

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(GuidedLearningController.class);
    private final Logger reactorHooksLogger = (Logger) LoggerFactory.getLogger(ReactorHooksConfig.class);
    private ExpectedLogEvents controllerLogEvents;
    private ExpectedLogEvents reactorHooksLogEvents;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void captureExpectedOverflowLog() {
        controllerLogEvents = ExpectedLogEvents.capture(controllerLogger);
        reactorHooksLogEvents = ExpectedLogEvents.capture(reactorHooksLogger);
    }

    @AfterEach
    void stopCapturingExpectedOverflowLog() {
        reactorHooksLogEvents.close();
        controllerLogEvents.close();
    }

    @Test
    void streamBufferOverflowDoesNotPersistPartialGuidedAnswer() throws JsonProcessingException {
        GuidedLearningService guidedLearningService = mock(GuidedLearningService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        GuidedLearningController guidedLearningController = new GuidedLearningController(
                guidedLearningService,
                chatMemoryService,
                streamingService,
                new ExceptionResponseBuilder(),
                mock(MarkdownService.class),
                new SseSupport(
                        objectMapper,
                        new SseStatusContractCatalog(objectMapper, new ClassPathResource("sse-status-contracts.json"))),
                new AppProperties());
        Throwable streamBufferOverflowFailure = Exceptions.failWithOverflow();
        Flux<String> partialAnswerThenOverflow = Flux.just("partial guided answer")
                .concatWith(Mono.delay(Duration.ofMillis(50)).thenMany(Flux.error(streamBufferOverflowFailure)));

        when(streamingService.isAvailable()).thenReturn(true);
        when(guidedLearningService.getLesson(LESSON_SLUG)).thenReturn(Optional.of(listedLesson()));
        when(chatMemoryService.getHistory(SESSION_ID)).thenReturn(List.of());
        when(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), eq(LESSON_SLUG), eq(USER_QUERY)))
                .thenReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        when(guidedLearningService.citationOutcomeForContextDocuments(anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(
                        Mono.just(new StreamingResult(partialAnswerThenOverflow, RateLimitService.ApiProvider.OPENAI)));
        when(streamingService.isRecoverableStreamingFailure(streamBufferOverflowFailure))
                .thenReturn(true);

        List<ServerSentEvent<String>> streamEvents = guidedLearningController.stream(
                        new GuidedStreamRequest(SESSION_ID, LESSON_SLUG, USER_QUERY), new MockHttpServletResponse())
                .collectList()
                .block();

        assertEquals(EVENT_STATUS, streamEvents.getFirst().event());
        assertTrue(streamEvents.stream().anyMatch(streamEvent -> EVENT_TEXT.equals(streamEvent.event())));
        ServerSentEvent<String> terminalErrorEvent = streamEvents.getLast();
        assertEquals(EVENT_ERROR, terminalErrorEvent.event());
        SseSupport.SseEventPayload terminalError = objectMapper.readValue(
                Objects.requireNonNull(terminalErrorEvent.data(), "terminal error event data"),
                SseSupport.SseEventPayload.class);
        assertEquals(STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR, terminalError.code());
        assertEquals(Boolean.TRUE, terminalError.retryable());
        assertEquals(STATUS_STAGE_STREAM, terminalError.stage());
        assertTrue(controllerLogEvents.events().stream().anyMatch(logEvent -> logEvent.getLevel() == Level.ERROR));
        verify(chatMemoryService, never()).addExchange(eq(SESSION_ID), eq(USER_QUERY), any());
    }

    private static GuidedLesson listedLesson() {
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(LESSON_SLUG);
        listedLesson.setTitle("Sealed Classes");
        return listedLesson;
    }
}
