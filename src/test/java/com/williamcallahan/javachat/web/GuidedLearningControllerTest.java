package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
    SseSupport sseSupport;

    @Test
    void guided_enrich_filters_empty_strings_and_whitespace() throws Exception {
        Enrichment testEnrichment = new Enrichment();
        testEnrichment.setHints(List.of(" ", "Hint"));
        testEnrichment.setReminders(List.of("", "Remember"));
        testEnrichment.setBackground(List.of(" Background ", "\t"));
        given(guidedLearningService.getLesson("intro")).willReturn(Optional.of(listedLesson("intro")));
        given(guidedLearningService.enrichmentForLesson(anyString())).willReturn(testEnrichment);

        mockMvc.perform(get("/api/guided/enrich").param("slug", "intro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hints", contains("Hint")))
                .andExpect(jsonPath("$.reminders", contains("Remember")))
                .andExpect(jsonPath("$.background", contains("Background")));
    }

    @Test
    void lessonNotFoundReturnsNotFoundAndNeutralizesControlCharacters() throws Exception {
        String hostileSlug = "intro\nERROR: forged log entry";
        given(guidedLearningService.getLesson(hostileSlug)).willReturn(Optional.empty());

        MvcResult failedResponse = mockMvc.perform(get("/api/guided/lesson").param("slug", hostileSlug))
                .andExpect(status().isNotFound())
                .andReturn();

        assertEquals(
                "Unknown lesson slug: intro?ERROR: forged log entry",
                failedResponse.getResponse().getErrorMessage());
    }

    @Test
    void unknownBlankAndOmittedSlugsReturnNotFoundBeforeAnyGuidedWorkStarts() throws Exception {
        String unknownLessonSlug = "unknown-guided-lesson";
        String blankLessonSlug = "";
        given(guidedLearningService.getLesson(unknownLessonSlug)).willReturn(Optional.empty());
        given(guidedLearningService.getLesson(blankLessonSlug)).willReturn(Optional.empty());
        given(guidedLearningService.getLesson(isNull())).willReturn(Optional.empty());

        assertNotFound(get("/api/guided/citations").param("slug", unknownLessonSlug), unknownLessonSlug);
        assertNotFound(get("/api/guided/enrich").param("slug", unknownLessonSlug), unknownLessonSlug);
        assertNotFound(get("/api/guided/content").param("slug", unknownLessonSlug), unknownLessonSlug);
        assertNotFound(get("/api/guided/content/html").param("slug", unknownLessonSlug), unknownLessonSlug);
        assertNotFound(get("/api/guided/content/stream").param("slug", unknownLessonSlug), unknownLessonSlug);
        assertNotFound(
                post("/api/guided/stream")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"guided:unknown\",\"slug\":\"" + unknownLessonSlug
                                + "\",\"latest\":\"Explain loops\"}"),
                unknownLessonSlug);
        assertNotFound(get("/api/guided/citations").param("slug", blankLessonSlug), blankLessonSlug);
        assertNotFound(get("/api/guided/enrich").param("slug", blankLessonSlug), blankLessonSlug);
        assertNotFound(get("/api/guided/content").param("slug", blankLessonSlug), blankLessonSlug);
        assertNotFound(get("/api/guided/content/html").param("slug", blankLessonSlug), blankLessonSlug);
        assertNotFound(get("/api/guided/content/stream").param("slug", blankLessonSlug), blankLessonSlug);
        assertNotFound(
                post("/api/guided/stream")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"guided:blank\",\"slug\":\"\",\"latest\":\"Explain loops\"}"),
                blankLessonSlug);
        assertMissingSlugNotFound(get("/api/guided/lesson"));
        assertMissingSlugNotFound(get("/api/guided/citations"));
        assertMissingSlugNotFound(get("/api/guided/enrich"));
        assertMissingSlugNotFound(get("/api/guided/content"));
        assertMissingSlugNotFound(get("/api/guided/content/html"));
        assertMissingSlugNotFound(get("/api/guided/content/stream"));
        assertMissingSlugNotFound(post("/api/guided/stream")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"guided:missing\",\"latest\":\"Explain loops\"}"));

        verify(guidedLearningService, never()).citationsForLesson(anyString());
        verify(guidedLearningService, never()).enrichmentForLesson(anyString());
        verify(guidedLearningService, never()).streamLessonContent(anyString());
        verify(guidedLearningService, never())
                .buildStructuredGuidedPromptWithContext(anyList(), anyString(), anyString());
        verifyNoInteractions(chatMemoryService, openAIStreamingService);
    }

    private void assertNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder,
            String rejectedLessonSlug)
            throws Exception {
        MvcResult failedResponse =
                mockMvc.perform(requestBuilder).andExpect(status().isNotFound()).andReturn();
        assertEquals(
                "Unknown lesson slug: " + rejectedLessonSlug,
                failedResponse.getResponse().getErrorMessage());
    }

    private void assertMissingSlugNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder)
            throws Exception {
        mockMvc.perform(requestBuilder).andExpect(status().isNotFound());
    }

    private static GuidedLesson listedLesson(String lessonSlug) {
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(lessonSlug);
        listedLesson.setTitle("Lesson");
        return listedLesson;
    }
}
