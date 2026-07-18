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
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_LEGACY_ALIAS = "Spring Framework Complete";
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH = "spring-framework-complete";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_PATH = "spring-framework";

    private final DocumentProcessor documentProcessor =
            new DocumentProcessor(mock(DocumentationIngestionUseCase.class), mock(ProgressTracker.class));

    @Test
    void includesQuickSetsOnlyForBlankQuickEnabledAndAllFiltersInTheSameOrder() {
        List<DocumentationSet> blankQuickEnabledSets = selectDocumentationSets("", true);
        List<DocumentationSet> blankDefaultSets = selectDocumentationSets("", false);
        List<DocumentationSet> allSelectorSets = selectDocumentationSets(DOCSET_ALL_SELECTOR, false);

        assertEquals(blankQuickEnabledSets, allSelectorSets);
        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_AI_QUICK_PATH));
    }

    @Test
    void requiresExactOfficialMirrorPathsWhileRetainingLegacyAliases() {
        assertEquals(
                List.of(DOCSET_JAVA_25_COMPLETE_PATH),
                relativePaths(selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_PATH, false)));

        DocumentProcessor.DocumentProcessingException selectionFailure = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_UPPERCASE_PATH, false));

        assertTrue(selectionFailure.getMessage().startsWith(DOCSET_NO_MATCH_MESSAGE_PREFIX));
        assertEquals(
                List.of(DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH),
                relativePaths(selectDocumentationSets(DOCSET_SPRING_FRAMEWORK_COMPLETE_LEGACY_ALIAS, false)));
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
