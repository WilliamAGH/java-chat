package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_CITATION;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies guided chat SSE streams emit a terminal citation event for the UI citation panel.
 *
 * <p>This guards against regressions where guided mode would rely on inline footnote markers like
 * {@code [1]} instead of emitting structured citation payloads.</p>
 */
@WebMvcTest(controllers = GuidedLearningController.class)
@Import({AppProperties.class, WebMvcConfig.class, SseStatusContractCatalog.class, SseSupport.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedSseCitationEventTest {

    private static final Logger GUIDED_LEARNING_CONTROLLER_LOGGER =
            (Logger) LoggerFactory.getLogger(GuidedLearningController.class);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GuidedLearningController guidedLearningController;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SseStatusContractCatalog statusContractCatalog;

    @MockitoBean
    GuidedLearningService guidedLearningService;

    @MockitoBean
    ChatMemoryService chatMemoryService;

    @MockitoBean
    MarkdownService markdownService;

    @MockitoBean
    ExceptionResponseBuilder exceptionResponseBuilder;

    @MockitoBean
    OpenAIStreamingService openAIStreamingService;

    @Test
    void guidedStreamEmitsCitationEvent() throws Exception {
        Document lessonContextDocument = Document.builder()
                .id("official-guided-context")
                .text("Official lesson context")
                .metadata("sourceKind", "official")
                .build();
        given(guidedLearningService.getLesson("intro")).willReturn(Optional.of(listedLesson("intro")));
        given(openAIStreamingService.isAvailable()).willReturn(true);
        given(chatMemoryService.getHistory(anyString())).willReturn(List.of());
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(new StreamingResult(Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI)));
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString()))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(lessonContextDocument)));
        given(guidedLearningService.citationOutcomeForContextDocuments(eq(List.of(lessonContextDocument))))
                .willReturn(new RetrievalService.CitationOutcome(
                        List.of(new Citation("https://example.com", "Example", "", "")), 0));

        var asyncResult = mockMvc.perform(post("/api/guided/stream")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"guided:test\",\"slug\":\"intro\",\"latest\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String aggregated = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(
                aggregated.contains("event:" + EVENT_CITATION) || aggregated.contains("event: " + EVENT_CITATION),
                "SSE stream should include a citation event. Response was:\n" + aggregated);
        assertTrue(
                aggregated.contains("https://example.com"),
                "Citation payload should include the citation URL. Response was:\n" + aggregated);
    }

    @Test
    void guidedStreamSurfacesPartialCitationConversionWithTheCanonicalStatus() throws JsonProcessingException {
        Document lessonContextDocument = Document.builder()
                .id("official-guided-context")
                .text("Official lesson context")
                .metadata("sourceKind", "official")
                .build();
        given(guidedLearningService.getLesson("intro")).willReturn(Optional.of(listedLesson("intro")));
        given(openAIStreamingService.isAvailable()).willReturn(true);
        given(chatMemoryService.getHistory(anyString())).willReturn(List.of());
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(new StreamingResult(Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI)));
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString()))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(lessonContextDocument)));
        given(guidedLearningService.citationOutcomeForContextDocuments(eq(List.of(lessonContextDocument))))
                .willReturn(new RetrievalService.CitationOutcome(
                        List.of(new Citation("https://example.com", "Example", "", "")), 1));

        List<ServerSentEvent<String>> streamEvents = Objects.requireNonNull(
                guidedLearningController.stream(
                                new GuidedStreamRequest("guided:test", "intro", "Hello"), new MockHttpServletResponse())
                        .collectList()
                        .block(),
                "guided stream events");

        int citationPartialFailureStatusIndex = -1;
        int citationEventIndex = -1;
        SseStatusContractCatalog.SseStatusContract citationContract = statusContractCatalog.citationPartialFailure();
        for (int eventIndex = 0; eventIndex < streamEvents.size(); eventIndex++) {
            ServerSentEvent<String> streamEvent = streamEvents.get(eventIndex);
            if (EVENT_CITATION.equals(streamEvent.event())) {
                citationEventIndex = eventIndex;
                continue;
            }
            if (!EVENT_STATUS.equals(streamEvent.event())) {
                continue;
            }
            SseSupport.SseEventPayload guidedStatus = objectMapper.readValue(
                    Objects.requireNonNull(streamEvent.data(), "guided status data"), SseSupport.SseEventPayload.class);
            if (citationContract.code().equals(guidedStatus.code())) {
                citationPartialFailureStatusIndex = eventIndex;
                assertEquals(Boolean.valueOf(citationContract.retryable()), guidedStatus.retryable());
                assertEquals(citationContract.stage(), guidedStatus.stage());
            }
        }

        assertTrue(citationPartialFailureStatusIndex >= 0, "guided stream should surface partial citation failure");
        assertTrue(citationEventIndex > citationPartialFailureStatusIndex, "warning should precede citations");
    }

    @Test
    void guidedContentStreamEmitsSseErrorWhenCuratedLessonStreamingFails() {
        given(guidedLearningService.getLesson("intro")).willReturn(Optional.of(listedLesson("intro")));
        given(guidedLearningService.streamLessonContent(anyString()))
                .willReturn(Flux.error(new IllegalStateException("Reranking request failed")));

        List<ServerSentEvent<String>> streamEvents;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(GUIDED_LEARNING_CONTROLLER_LOGGER)) {
            streamEvents = Objects.requireNonNull(
                    guidedLearningController
                            .streamLesson("intro", new MockHttpServletResponse())
                            .collectList()
                            .block(),
                    "guided lesson content stream events");

            assertEquals(1, expectedLogEvents.events().size());
            var streamFailureEvent = expectedLogEvents.events().getFirst();
            assertEquals(Level.ERROR, streamFailureEvent.getLevel());
            assertEquals("Guided lesson content stream error", streamFailureEvent.getFormattedMessage());
            assertNull(streamFailureEvent.getThrowableProxy());
        }

        ServerSentEvent<String> errorSseEvent = streamEvents.stream()
                .filter(streamEvent -> EVENT_ERROR.equals(streamEvent.event()))
                .findFirst()
                .orElseThrow();
        String serializedError = Objects.requireNonNull(errorSseEvent.data(), "guided lesson stream error payload");
        assertTrue(
                EVENT_ERROR.equals(errorSseEvent.event()), "SSE stream should include a lesson-content error event.");
        assertTrue(
                serializedError.contains("Lesson content stream failed"),
                "Error payload should identify lesson content stream failure. Payload was:\n" + serializedError);
    }

    private static GuidedLesson listedLesson(String lessonSlug) {
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(lessonSlug);
        listedLesson.setTitle("Introduction");
        return listedLesson;
    }
}
