package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies guided learning endpoints return expected data under WebMvcTest.
 */
@WebMvcTest(controllers = GuidedLearningController.class)
@Import({AppProperties.class, WebMvcConfig.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedLearningControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GuidedLearningService guidedLearningService;

    @MockitoBean
    ChatMemoryService chatMemoryService;

    @MockitoBean
    MarkdownService markdownService;

    @MockitoBean
    UnifiedMarkdownService unifiedMarkdownService;

    @MockitoBean
    ExceptionResponseBuilder exceptionResponseBuilder;

    @MockitoBean
    OpenAIStreamingService openAIStreamingService;

    @MockitoBean
    RetrievalService retrievalService;

    @MockitoBean
    SseSupport sseSupport;

    @Test
    void guided_enrich_filters_empty_strings_and_whitespace() throws Exception {
        Enrichment testEnrichment = new Enrichment();
        testEnrichment.setHints(List.of(" ", "Hint"));
        testEnrichment.setReminders(List.of("", "Remember"));
        testEnrichment.setBackground(List.of(" Background ", "\t"));
        given(guidedLearningService.enrichmentForLesson(anyString())).willReturn(testEnrichment);

        mockMvc.perform(get("/api/guided/enrich").param("slug", "intro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hints", contains("Hint")))
                .andExpect(jsonPath("$.reminders", contains("Remember")))
                .andExpect(jsonPath("$.background", contains("Background")));
    }
}
