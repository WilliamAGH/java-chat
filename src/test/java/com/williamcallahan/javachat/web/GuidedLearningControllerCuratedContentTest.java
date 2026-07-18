package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.WebMvcConfig;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/** Verifies guided content HTTP contracts resolve only authoritative curated lesson markdown. */
@WebMvcTest(controllers = GuidedLearningController.class)
@Import({AppProperties.class, WebMvcConfig.class, SseStatusContractCatalog.class, SseSupport.class})
@org.springframework.security.test.context.support.WithMockUser
class GuidedLearningControllerCuratedContentTest {
    private static final String CURATED_LESSON_MARKDOWN = "Curated lesson markdown";
    private static final String LISTED_LESSON_SLUG = "introduction-to-java";
    private static final String MISSING_CURATED_LESSON_SLUG = "missing-curated-lesson";
    private static final String UNKNOWN_LESSON_SLUG = "unknown-guided-lesson";

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(GuidedLearningController.class);
    private ExpectedLogEvents controllerLogEvents;

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

    @BeforeEach
    void startCapturingControllerLogs() {
        controllerLogEvents = ExpectedLogEvents.capture(controllerLogger);
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogEvents.close();
    }

    @Test
    void contentStreamEmitsServiceProvidedCuratedMarkdown() throws Exception {
        given(guidedLearningService.getLesson(LISTED_LESSON_SLUG))
                .willReturn(Optional.of(listedLesson(LISTED_LESSON_SLUG)));
        given(guidedLearningService.streamLessonContent(LISTED_LESSON_SLUG))
                .willReturn(Flux.just(CURATED_LESSON_MARKDOWN));

        MvcResult initialResponse = mockMvc.perform(
                        get("/api/guided/content/stream").param("slug", LISTED_LESSON_SLUG))
                .andExpect(request().asyncStarted())
                .andReturn();

        String serializedSse = mockMvc.perform(asyncDispatch(initialResponse))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(
                serializedSse.contains("event:" + EVENT_TEXT) || serializedSse.contains("event: " + EVENT_TEXT),
                () -> "Guided content stream must preserve the text SSE event contract: " + serializedSse);
        assertTrue(
                serializedSse.contains(CURATED_LESSON_MARKDOWN),
                () -> "Guided content stream must emit the curated markdown: " + serializedSse);
    }

    @Test
    void contentStreamReturnsNotFoundForUnlistedLessonSlug() throws Exception {
        given(guidedLearningService.getLesson(UNKNOWN_LESSON_SLUG)).willReturn(Optional.empty());

        MvcResult failedResponse = mockMvc.perform(
                        get("/api/guided/content/stream").param("slug", UNKNOWN_LESSON_SLUG))
                .andExpect(status().isNotFound())
                .andReturn();

        assertEquals(
                "Unknown lesson slug: " + UNKNOWN_LESSON_SLUG,
                failedResponse.getResponse().getErrorMessage());
    }

    @Test
    void contentStreamReturnsServerErrorForListedLessonWithoutPackagedMarkdown() throws Exception {
        given(guidedLearningService.getLesson(MISSING_CURATED_LESSON_SLUG))
                .willReturn(Optional.of(listedLesson(MISSING_CURATED_LESSON_SLUG)));
        given(guidedLearningService.streamLessonContent(MISSING_CURATED_LESSON_SLUG))
                .willThrow(
                        new GuidedLearningService.CuratedLessonResourceMissingException(MISSING_CURATED_LESSON_SLUG));

        MvcResult failedResponse = mockMvc.perform(
                        get("/api/guided/content/stream").param("slug", MISSING_CURATED_LESSON_SLUG))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertEquals(
                "Curated lesson content is unavailable",
                failedResponse.getResponse().getErrorMessage());
        assertTrue(controllerLogEvents.events().stream()
                .anyMatch(controllerAlert -> controllerAlert.getLevel() == Level.ERROR
                        && controllerAlert.getFormattedMessage().contains("no packaged markdown")));
    }

    private static GuidedLesson listedLesson(String lessonSlug) {
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(lessonSlug);
        listedLesson.setTitle("Listed Lesson");
        return listedLesson;
    }
}
