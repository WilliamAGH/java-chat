package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;

/**
 * Verifies guided learning endpoints return expected data under WebMvcTest.
 */
@WebMvcTest(controllers = GuidedLearningController.class)
@Import({AppProperties.class, WebMvcConfig.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedLearningControllerTest {

    @Autowired
    MockMvc mvc;

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

    @Test
    void guided_enrich_filters_empty_strings_and_whitespace() throws Exception {
        Enrichment e = new Enrichment();
        e.setHints(List.of(" ", "Hint"));
        e.setReminders(List.of("", "Remember"));
        e.setBackground(List.of(" Background ", "\t"));
        given(guidedLearningService.enrichmentForLesson(anyString())).willReturn(e);

        mvc.perform(get("/api/guided/enrich").param("slug", "intro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hints", contains("Hint")))
                .andExpect(jsonPath("$.reminders", contains("Remember")))
                .andExpect(jsonPath("$.background", contains("Background")));
    }
}
