package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.GuidedLesson;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verifies guided source references remain manifest-owned and project into the public lesson contract. */
class GuidedTOCProviderTest {
    private static final String SOURCE_KIND_OFFICIAL = "official";
    private static final String CANONICAL_SOURCE_REFERENCE_TEST_SLUG = "strings";
    private static final String CALLER_MUTATED_LESSON_TITLE = "Caller-mutated lesson title";
    private static final String UNKNOWN_SOURCE_REFERENCE_TEST_SLUG = "unknown-source-reference";
    private static final String UNKNOWN_SOURCE_REFERENCE = "missing-canonical-source";

    @Test
    void projectsManifestOwnedSourceReferencesIntoThePublicDocSetContract() throws JsonProcessingException {
        GuidedLesson listedLesson = new GuidedTOCProvider(new ObjectMapper())
                .findBySlug(CANONICAL_SOURCE_REFERENCE_TEST_SLUG)
                .orElseThrow();

        List<String> expectedDocSets = Stream.concat(
                        DocsSourceRegistry.documentationSources().stream()
                                .filter(documentationSource ->
                                        SOURCE_KIND_OFFICIAL.equals(documentationSource.sourceKind()))
                                .map(DocsSourceRegistry.DocumentationSource::docSet),
                        DocsSourceRegistry.javaApiDocumentationSources().stream()
                                .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath))
                .filter(listedLesson.sourceReferences()::contains)
                .toList();

        assertEquals(expectedDocSets, listedLesson.getDocSet());
        String serializedLesson = new ObjectMapper().writeValueAsString(listedLesson);
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
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(new ObjectMapper());

        List<GuidedLesson> firstSnapshot = tocProvider.getTOC();
        GuidedLesson callerLesson = firstSnapshot.getFirst();
        String originalLessonTitle = callerLesson.getTitle();

        assertThrows(UnsupportedOperationException.class, firstSnapshot::removeFirst);
        callerLesson.setTitle(CALLER_MUTATED_LESSON_TITLE);

        GuidedLesson subsequentLesson = tocProvider.getTOC().getFirst();
        assertEquals(originalLessonTitle, subsequentLesson.getTitle());
    }
}
