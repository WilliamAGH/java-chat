package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.test.subscriber.TestSubscriber;

/** Verifies curated classpath lesson content remains the single source for guided lesson streaming. */
@JsonTest
class GuidedLessonCuratedContentTest {
    private static final String CURATED_LESSON_RESOURCE_DIRECTORY = "guided/lessons/";
    private static final String CURATED_LESSON_RESOURCE_PATTERN = "classpath*:guided/lessons/*.md";
    private static final String MARKDOWN_FILE_SUFFIX = ".md";
    private static final String MISSING_CURATED_LESSON_SLUG = "missing-curated-lesson";
    private static final Duration SLOW_SUBSCRIBER_COMPLETION_TIMEOUT = Duration.ofSeconds(1);
    private static final String TEST_JDK_VERSION = "25";

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void tocAndCuratedClasspathResourcesHaveExactSlugParity() throws IOException {
        GuidedTOCProvider tocProvider = curatedTocProvider();
        List<String> tocLessonSlugs =
                tocProvider.getTOC().stream().map(GuidedLesson::getSlug).toList();
        List<String> curatedResourceSlugs = curatedClasspathLessonSlugs();

        assertUniqueLessonSlugs(tocLessonSlugs, "guided TOC");
        assertUniqueLessonSlugs(curatedResourceSlugs, "curated lesson classpath resources");
        assertEquals(Set.copyOf(tocLessonSlugs), Set.copyOf(curatedResourceSlugs));
        for (String curatedLessonSlug : curatedResourceSlugs) {
            String curatedLessonMarkdown = readCuratedLessonMarkdown(curatedLessonSlug);
            assertFalse(
                    curatedLessonMarkdown.isBlank(),
                    () -> "Curated lesson markdown must not be blank: " + curatedLessonSlug);
            assertFalse(
                    curatedLessonMarkdown.startsWith("# "),
                    () -> "GuidedLessonHeader owns the sole level-one heading for: " + curatedLessonSlug);
        }
    }

    @Test
    void streamsEveryListedLessonFromItsCuratedClasspathMarkdown() throws IOException {
        GuidedTOCProvider tocProvider = curatedTocProvider();
        GuidedLearningService guidedLearningService = curatedLessonService(tocProvider);

        for (GuidedLesson listedLesson : tocProvider.getTOC()) {
            String canonicalLessonSlug = listedLesson.getSlug();
            List<String> streamedMarkdownChunks = Objects.requireNonNull(
                    guidedLearningService
                            .streamLessonContent(canonicalLessonSlug)
                            .collectList()
                            .block(SLOW_SUBSCRIBER_COMPLETION_TIMEOUT),
                    "Curated lesson stream must complete with markdown");

            assertEquals(
                    readCuratedLessonMarkdown(canonicalLessonSlug),
                    String.join("", streamedMarkdownChunks),
                    () -> "Guided lesson stream must use the curated markdown for: " + canonicalLessonSlug);
        }
    }

    @Test
    void streamsCuratedMarkdownToSubscriberThatRequestsAfterSubscription() throws IOException {
        GuidedTOCProvider tocProvider = curatedTocProvider();
        GuidedLesson firstListedLesson = tocProvider.getTOC().get(0);
        String canonicalLessonSlug = firstListedLesson.getSlug();
        TestSubscriber<String> slowSubscriber =
                TestSubscriber.<String>builder().initialRequest(0).build();

        curatedLessonService(tocProvider)
                .streamLessonContent(canonicalLessonSlug)
                .subscribe(slowSubscriber);

        assertTrue(slowSubscriber.getReceivedOnNext().isEmpty());
        assertFalse(slowSubscriber.isTerminated());

        slowSubscriber.request(1);
        slowSubscriber.block(SLOW_SUBSCRIBER_COMPLETION_TIMEOUT);

        assertTrue(slowSubscriber.isTerminatedComplete());
        assertEquals(List.of(readCuratedLessonMarkdown(canonicalLessonSlug)), slowSubscriber.getReceivedOnNext());
    }

    @Test
    void everyTocLessonDeclaresTechnologyAndCanonicalOfficialDocSetScope() {
        for (GuidedLesson guidedLesson : curatedTocProvider().getTOC()) {
            assertFalse(guidedLesson.getTechnology().isBlank());
            assertFalse(guidedLesson.getDocSet().isEmpty());
        }
    }

    @Test
    void modulesLessonRendersTheGreetingWithoutDuplicatePunctuation() throws IOException {
        String modulesLessonMarkdown = readCuratedLessonMarkdown("modules");

        assertTrue(modulesLessonMarkdown.contains("The program prints `Welcome, Maya.` If"));
        assertFalse(modulesLessonMarkdown.contains("The program prints `Welcome, Maya.`."));
    }

    @Test
    void rejectsListedLessonWithoutPackagedCuratedMarkdown() {
        GuidedTOCProvider tocProvider = mock(GuidedTOCProvider.class);
        GuidedLesson listedLesson = new GuidedLesson();
        listedLesson.setSlug(MISSING_CURATED_LESSON_SLUG);
        when(tocProvider.findBySlug(MISSING_CURATED_LESSON_SLUG)).thenReturn(Optional.of(listedLesson));

        GuidedLearningService.CuratedLessonResourceMissingException missingResourceFailure = assertThrows(
                GuidedLearningService.CuratedLessonResourceMissingException.class,
                () -> curatedLessonService(tocProvider).streamLessonContent(MISSING_CURATED_LESSON_SLUG));

        assertEquals(
                "Curated lesson resource is missing for TOC lesson: " + MISSING_CURATED_LESSON_SLUG,
                missingResourceFailure.getMessage());
    }

    private GuidedTOCProvider curatedTocProvider() {
        return new GuidedTOCProvider(objectMapper);
    }

    private static GuidedLearningService curatedLessonService(GuidedTOCProvider tocProvider) {
        return new GuidedLearningService(
                tocProvider,
                mock(RetrievalService.class),
                mock(EnrichmentService.class),
                mock(ChatService.class),
                mock(SystemPromptConfig.class),
                new MarkdownService(new UnifiedMarkdownService()),
                TEST_JDK_VERSION);
    }

    private static List<String> curatedClasspathLessonSlugs() throws IOException {
        Resource[] curatedLessonResources =
                new PathMatchingResourcePatternResolver().getResources(CURATED_LESSON_RESOURCE_PATTERN);
        return Arrays.stream(curatedLessonResources)
                .map(GuidedLessonCuratedContentTest::lessonSlugFromResource)
                .toList();
    }

    private static String lessonSlugFromResource(Resource curatedLessonResource) {
        String resourceFileName =
                Objects.requireNonNull(curatedLessonResource.getFilename(), "Curated lesson resource filename");
        if (!resourceFileName.endsWith(MARKDOWN_FILE_SUFFIX)) {
            throw new IllegalArgumentException("Curated lesson resource must end with .md: " + resourceFileName);
        }
        return resourceFileName.substring(0, resourceFileName.length() - MARKDOWN_FILE_SUFFIX.length());
    }

    private static String readCuratedLessonMarkdown(String canonicalLessonSlug) throws IOException {
        String lessonResourcePath = CURATED_LESSON_RESOURCE_DIRECTORY + canonicalLessonSlug + MARKDOWN_FILE_SUFFIX;
        InputStream curatedLessonStream =
                GuidedLessonCuratedContentTest.class.getClassLoader().getResourceAsStream(lessonResourcePath);
        assertNotNull(
                curatedLessonStream,
                () -> "Curated lesson resource is absent from the classpath: " + lessonResourcePath);
        try (curatedLessonStream) {
            return new String(curatedLessonStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertUniqueLessonSlugs(List<String> lessonSlugs, String catalogDescription) {
        assertEquals(
                lessonSlugs.size(),
                Set.copyOf(lessonSlugs).size(),
                () -> "Duplicate lesson slug in " + catalogDescription);
    }
}
