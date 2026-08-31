package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.williamcallahan.javachat.application.ingestion.LocalDocumentationIngestionUseCase;
import com.williamcallahan.javachat.config.QdrantIndexInitializer;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies that the CLI selects its direct-owned documentation sets without changing filter semantics. */
class DocumentProcessorSelectionTest {
    private static final String DOCSET_ALL_SELECTOR = "all";
    private static final String DOCSET_JAVA_21_COMPLETE_PATH = "java/java21-complete";
    private static final String DOCSET_JAVA_24_COMPLETE_PATH = "java/java24-complete";
    private static final String DOCSET_JAVA_25_COMPLETE_PATH = "java/java25-complete";
    private static final String DOCSET_JAVA_26_COMPLETE_PATH = "java/java26-complete";
    private static final String DOCSET_JAVA_25_COMPLETE_UPPERCASE_PATH = "JAVA/JAVA25-COMPLETE";
    private static final String DOCSET_UNKNOWN_MESSAGE_PREFIX = "DOCS_SETS contains unknown selectors: ";
    private static final String DOCSET_SPRING_AI_QUICK_PATH = "spring-ai";
    private static final String DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH = "spring-framework-reference";
    private static final String DOCSET_SPRING_FRAMEWORK_API_PATH = "spring-framework-api";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_PATH = "spring-framework";
    private static final String DOCSET_GROOVY_MIRROR_PATH = "groovy/5.0.7";

    private final DocumentProcessor documentProcessor =
            new DocumentProcessor(mock(LocalDocumentationIngestionUseCase.class), mock(ProgressTracker.class));

    @Test
    void separatesQuickMirrorsFromCanonicalAllSelection() {
        List<DocumentationSet> blankQuickEnabledSets = selectDocumentationSets(null, true);
        List<DocumentationSet> blankDefaultSets = selectDocumentationSets(null, false);
        List<DocumentationSet> allSelectorSets = selectDocumentationSets(DOCSET_ALL_SELECTOR, false);

        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertTrue(containsRelativePath(blankQuickEnabledSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertFalse(containsRelativePath(allSelectorSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertFalse(containsRelativePath(allSelectorSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertEquals(blankDefaultSets, allSelectorSets);
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_FRAMEWORK_QUICK_PATH));
        assertFalse(containsRelativePath(blankDefaultSets, DOCSET_SPRING_AI_QUICK_PATH));
        assertTrue(containsRelativePath(allSelectorSets, DOCSET_JAVA_21_COMPLETE_PATH));
        assertFalse(containsRelativePath(allSelectorSets, DOCSET_JAVA_24_COMPLETE_PATH));
        assertTrue(containsRelativePath(allSelectorSets, DOCSET_JAVA_25_COMPLETE_PATH));
        assertTrue(containsRelativePath(allSelectorSets, DOCSET_JAVA_26_COMPLETE_PATH));
    }

    @Test
    void requiresExactCanonicalMirrorPathsAndRejectsAggregateAliases() {
        assertEquals(
                List.of(DOCSET_JAVA_25_COMPLETE_PATH),
                relativePaths(selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_PATH, false)));

        DocumentProcessor.DocumentProcessingException selectionFailure = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_UPPERCASE_PATH, false));

        assertTrue(selectionFailure.getMessage().startsWith(DOCSET_UNKNOWN_MESSAGE_PREFIX));
        assertEquals(
                List.of(DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH, DOCSET_SPRING_FRAMEWORK_API_PATH),
                relativePaths(selectDocumentationSets(
                        DOCSET_SPRING_FRAMEWORK_REFERENCE_PATH + "," + DOCSET_SPRING_FRAMEWORK_API_PATH, false)));
        assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets("spring-framework-complete", false));
    }

    @Test
    void rejectsUnknownSelectorInsteadOfSilentlyProcessingValidSubset() {
        DocumentProcessor.DocumentProcessingException selectionFailure = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(DOCSET_JAVA_25_COMPLETE_PATH + ",unknown-documentation", false));

        assertTrue(selectionFailure
                .getMessage()
                .startsWith(DOCSET_UNKNOWN_MESSAGE_PREFIX + "unknown-documentation. Available doc sets: "));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ",",
                ",java/java25-complete",
                "java/java25-complete,,spring-framework-reference",
                "java/java25-complete,",
                "   "
            })
    void rejectsBlankSelectorTokens(final String malformedDocSetFilter) {
        DocumentProcessor.DocumentProcessingException selectionFailure = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> selectDocumentationSets(malformedDocSetFilter, false));

        assertEquals("DOCS_SETS contains a blank selector", selectionFailure.getMessage());
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

    @Test
    void initializesQdrantBeforeStartingDocumentProcessing() {
        QdrantIndexInitializer qdrantIndexInitializer = mock(QdrantIndexInitializer.class);
        IllegalStateException initializationFailure = new IllegalStateException("initialization failed");
        doThrow(initializationFailure).when(qdrantIndexInitializer).requireCollectionsAndIndexesReady();

        IllegalStateException observedFailure = assertThrows(
                IllegalStateException.class,
                () -> documentProcessor.processDocuments(qdrantIndexInitializer).run());

        assertSame(initializationFailure, observedFailure);
        verify(qdrantIndexInitializer).requireCollectionsAndIndexesReady();
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
