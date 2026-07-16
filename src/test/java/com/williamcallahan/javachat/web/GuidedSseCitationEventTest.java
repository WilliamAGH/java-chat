package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_CITATION;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
@Import({AppProperties.class, WebMvcConfig.class, SseSupport.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedSseCitationEventTest {

    @Autowired
    MockMvc mockMvc;

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

    @MockitoBean
    RetrievalService retrievalService;

    @Test
    void guidedStreamEmitsCitationEvent() throws Exception {
        given(openAIStreamingService.isAvailable()).willReturn(true);
        given(chatMemoryService.getHistory(anyString())).willReturn(List.of());
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Mono.just(new StreamingResult(Flux.just("Hello"), RateLimitService.ApiProvider.OPENAI)));
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString()))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of()));
        given(guidedLearningService.citationsForBookDocuments(anyList()))
                .willReturn(List.of(new Citation("https://example.com", "Example", "", "")));

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
    void guidedContentStreamEmitsSseErrorWhenLessonGenerationFails() throws Exception {
        given(guidedLearningService.getCachedLessonMarkdown(anyString())).willReturn(Optional.empty());
        given(guidedLearningService.streamLessonContent(anyString()))
                .willReturn(Flux.error(new IllegalStateException("Reranking request failed")));

        var asyncResult = mockMvc.perform(get("/api/guided/content/stream").param("slug", "intro"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String aggregated = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(
                aggregated.contains("event:" + EVENT_ERROR) || aggregated.contains("event: " + EVENT_ERROR),
                "SSE stream should include an error event. Response was:\n" + aggregated);
        assertTrue(
                aggregated.contains("Lesson content stream failed"),
                "Error payload should identify lesson content stream failure. Response was:\n" + aggregated);
    }
}
