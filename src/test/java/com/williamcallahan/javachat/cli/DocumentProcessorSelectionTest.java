package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.williamcallahan.javachat.application.ingestion.DocumentationIngestionUseCase;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that the CLI selects its direct-owned documentation sets without changing filter semantics. */
class DocumentProcessorSelectionTest {
    private static final String DOCSET_ALL_SELECTOR = "all";
    private static final String DOCSET_JAVA_25_COMPLETE_PATH = "java/java25-complete";
    private static final String DOCSET_JAVA_25_COMPLETE_UPPERCASE_PATH = "JAVA/JAVA25-COMPLETE";
    private static final String DOCSET_NO_MATCH_MESSAGE_PREFIX =
            "DOCS_SETS matched no documentation sets. Available doc sets: ";
    private static final String DOCSET_SPRING_AI_QUICK_PATH = "spring-ai";
    private static final String DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH = "spring-framework-reference";
    private static final String DOCSET_SPRING_FRAMEWORK_API_PATH = "spring-framework-api";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_PATH = "spring-framework";
    private static final String DOCSET_GROOVY_MIRROR_PATH = "groovy/5.0.7";

    private final DocumentProcessor documentProcessor =
            new DocumentProcessor(mock(DocumentationIngestionUseCase.class), mock(ProgressTracker.class));

    @Test
    void separatesQuickMirrorsFromCanonicalAllSelection() {
        List<DocumentationSet> blankQuickEnabledSets = selectDocumentationSets("", true);
        List<DocumentationSet> blankDefaultSets = selectDocumentationSets("", false);
        List<DocumentationSet> allSelectorSets = selectDocumentationSets(DOCSET_ALL_SELECTOR, false);

        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertFalse(containsRelativePath(allSelectorSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertFalse(containsRelativePath(allSelectorSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertEquals(blankDefaultSets, allSelectorSets);
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_AI_QUICK_PATH));
    }

    @Test
    void requiresExactCanonicalMirrorPathsAndRejectsAggregateAliases() {
        assertEquals(
                List.of(DOCSET_JAVA_25_COMPLETE_PATH),
                relativePaths(selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_PATH, false)));

        DocumentProcessor.DocumentProcessingException selectionFailure = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_UPPERCASE_PATH, false));

        assertTrue(selectionFailure.getMessage().startsWith(DOCSET_NO_MATCH_MESSAGE_PREFIX));
        assertEquals(
                List.of(DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH, DOCSET_SPRING_FRAMEWORK_API_PATH),
                relativePaths(selectDocumentationSets(
                        DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH + "," + DOCSET_SPRING_FRAMEWORK_API_PATH, false)));
        assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets("spring-framework-complete", false));
    }

    @Test
    void rejectsMixedQuickAndCanonicalSelection() {
        assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(
                        DOCSET_SPRING_FRAMEWORK_QUICK_PATH + "," + DOCSET_JAVA_25_COMPLETE_PATH, false));
    }

    @Test
    void carriesExactIndexedDocSetSeparatelyFromVersionedMirrorPath() {
        DocumentationSet groovyDocumentationSet =
                selectDocumentationSets(DOCSET_GROOVY_MIRROR_PATH, false).getFirst();

        assertEquals(DOCSET_GROOVY_MIRROR_PATH, groovyDocumentationSet.relativePath());
        assertEquals("groovy", groovyDocumentationSet.indexedDocSet());
    }

    private List<DocumentationSet> selectDocumentationSets(final String docSetFilter, final boolean includeQuickSets) {
        return documentProcessor.selectDocumentationSets(docSetFilter, includeQuickSets);
    }

    private static boolean containsRelativePath(
            final List<DocumentationSet> documentationSets, final String expectedRelativePath) {
        return documentationSets.stream().map(DocumentationSet::relativePath).anyMatch(expectedRelativePath::equals);
    }

    private static List<String> relativePaths(final List<DocumentationSet> documentationSets) {
        return documentationSets.stream().map(DocumentationSet::relativePath).toList();
    }
}
