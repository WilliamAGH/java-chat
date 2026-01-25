package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GuidedLearningController.class)
@Import({AppProperties.class, WebMvcConfig.class, SseSupport.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedSseCitationEventTest {

    @Autowired
    MockMvc mvc;

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
        given(openAIStreamingService.isAvailable()).willReturn(true);
        given(chatMemoryService.getHistory(anyString())).willReturn(List.of());
        given(openAIStreamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .willReturn(Flux.just("Hello"));
        given(guidedLearningService.buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString()))
                .willReturn(new GuidedLearningService.GuidedChatPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1),
                        List.of()
                ));
        given(guidedLearningService.citationsForBookDocuments(anyList()))
                .willReturn(List.of(new Citation("https://example.com", "Example", "", "")));

        var pending = mvc.perform(post("/api/guided/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"guided:test\",\"slug\":\"intro\",\"latest\":\"Hello\"}"))
                .andReturn()
                .getRequest();

        var asyncResult = mvc.perform(post("/api/guided/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"guided:test\",\"slug\":\"intro\",\"latest\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String aggregated = mvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(aggregated.contains("event:citation") || aggregated.contains("event: citation"),
                "SSE stream should include a citation event");
        assertTrue(aggregated.contains("https://example.com"),
                "Citation payload should include the citation URL");
    }
}
