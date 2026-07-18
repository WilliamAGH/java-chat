package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.GuidedLesson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** Verifies guided source references remain manifest-owned and project into the public lesson contract. */
@JsonTest
class GuidedTOCProviderTest {
    private static final String CANONICAL_SOURCE_REFERENCE_TEST_SLUG = "strings";
    private static final String CALLER_MUTATED_LESSON_TITLE = "Caller-mutated lesson title";
    private static final String UNKNOWN_SOURCE_REFERENCE_TEST_SLUG = "unknown-source-reference";
    private static final String UNKNOWN_SOURCE_REFERENCE = "missing-canonical-source";
    private static final int CONCURRENT_TOC_CALLER_COUNT = 8;
    private static final int CONCURRENT_TOC_TEST_TIMEOUT_SECONDS = 5;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void projectsManifestOwnedSourceReferencesIntoThePublicDocSetContract() throws JsonProcessingException {
        GuidedLesson listedLesson = new GuidedTOCProvider(objectMapper)
                .findBySlug(CANONICAL_SOURCE_REFERENCE_TEST_SLUG)
                .orElseThrow();

        List<String> expectedDocSets = DocsSourceRegistry.officialDocumentationSourceIdentities().stream()
                .filter(listedLesson.sourceReferences()::contains)
                .toList();

        assertEquals(expectedDocSets, listedLesson.getDocSet());
        String serializedLesson = objectMapper.writeValueAsString(listedLesson);
        assertFalse(serializedLesson.contains("\"sourceReferences\""));
        assertTrue(serializedLesson.contains("\"docSet\""));
    }

    @Test
    void rejectsUnknownCanonicalSourceReference() {
        GuidedLesson invalidLesson = new GuidedLesson();
        invalidLesson.setSlug(UNKNOWN_SOURCE_REFERENCE_TEST_SLUG);
        invalidLesson.setTechnology("Example technology");
        invalidLesson.setSourceReferences(List.of(UNKNOWN_SOURCE_REFERENCE));

        IllegalStateException invalidSourceScope = assertThrows(
                IllegalStateException.class,
                () -> GuidedTOCProvider.projectOfficialSourceScopes(List.of(invalidLesson)));

        assertTrue(invalidSourceScope.getCause().getMessage().contains(UNKNOWN_SOURCE_REFERENCE));
    }

    @Test
    void returnsImmutableDetachedLessonSnapshots() {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);

        List<GuidedLesson> firstSnapshot = tocProvider.getTOC();
        GuidedLesson callerLesson = firstSnapshot.getFirst();
        String originalLessonTitle = callerLesson.getTitle();

        assertThrows(UnsupportedOperationException.class, firstSnapshot::removeFirst);
        callerLesson.setTitle(CALLER_MUTATED_LESSON_TITLE);

        GuidedLesson subsequentLesson = tocProvider.getTOC().getFirst();
        assertEquals(originalLessonTitle, subsequentLesson.getTitle());
    }

    @Test
    void loadsTocOnceAndReturnsDetachedSnapshotsToConcurrentCallers()
            throws ExecutionException, InterruptedException, TimeoutException {
        ObjectMapper tocLoadingObjectMapper = spy(objectMapper.copy());
        ObjectMapper suppliedObjectMapper = spy(objectMapper);
        doReturn(tocLoadingObjectMapper).when(suppliedObjectMapper).copy();
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(suppliedObjectMapper);
        ExecutorService tocCallerExecutor = Executors.newFixedThreadPool(CONCURRENT_TOC_CALLER_COUNT);
        CyclicBarrier tocStartBarrier = new CyclicBarrier(CONCURRENT_TOC_CALLER_COUNT);
        List<Future<List<GuidedLesson>>> callerSnapshotFutures = new ArrayList<>(CONCURRENT_TOC_CALLER_COUNT);

        try {
            for (int callerIndex = 0; callerIndex < CONCURRENT_TOC_CALLER_COUNT; callerIndex++) {
                callerSnapshotFutures.add(tocCallerExecutor.submit(() -> {
                    tocStartBarrier.await();
                    return tocProvider.getTOC();
                }));
            }

            List<GuidedLesson> firstCallerSnapshot =
                    callerSnapshotFutures.getFirst().get(CONCURRENT_TOC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> expectedLessonSlugs =
                    firstCallerSnapshot.stream().map(GuidedLesson::getSlug).toList();
            for (int callerIndex = 1; callerIndex < callerSnapshotFutures.size(); callerIndex++) {
                List<GuidedLesson> callerSnapshot = callerSnapshotFutures
                        .get(callerIndex)
                        .get(CONCURRENT_TOC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotSame(firstCallerSnapshot, callerSnapshot);
                assertNotSame(firstCallerSnapshot.getFirst(), callerSnapshot.getFirst());
                assertEquals(
                        expectedLessonSlugs,
                        callerSnapshot.stream().map(GuidedLesson::getSlug).toList());
            }

            verify(tocLoadingObjectMapper, times(1)).readerFor(ArgumentMatchers.<TypeReference<?>>any());
        } finally {
            tocCallerExecutor.shutdownNow();
        }
    }
}
