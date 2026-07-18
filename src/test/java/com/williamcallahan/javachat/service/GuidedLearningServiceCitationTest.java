package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** Verifies every guided retrieval flow is grounded in its lesson-owned official source scope. */
@JsonTest
class GuidedLearningServiceCitationTest {
    private static final String LESSON_SLUG = "strings";
    private static final String UNKNOWN_LESSON_SLUG = "unknown-guided-lesson";
    private static final String TEST_JDK_VERSION = "25";
    private static final String USER_QUESTION = "How does substring work?";
    private static final String OFFICIAL_SOURCE_TEXT = "String.substring returns a new string.";
    private static final String LOOPS_LESSON_SLUG = "loops";
    private static final String KOTLIN_LESSON_SLUG = "kotlin-on-the-jvm";
    private static final String CURATED_LESSON_RESOURCE_DIRECTORY = "guided/lessons/";
    private static final String CURATED_LESSON_FILE_SUFFIX = ".md";

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void guidedRetrievalFlowsUseTheLessonOfficialDocSetConstraint() {
        GuidedLesson guidedLesson = guidedLesson();
        GuidedTOCProvider tocProvider = mock(GuidedTOCProvider.class);
        when(tocProvider.findBySlug(LESSON_SLUG)).thenReturn(Optional.of(guidedLesson));

        Document officialSourceDocument = officialSourceDocument(guidedLesson);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class)))
                .thenReturn(List.of(officialSourceDocument));
        Citation officialCitation = new Citation(officialSourceUrl(guidedLesson), "Strings", "", "substring");
        when(retrievalService.discoverCitations(anyString(), any(RetrievalConstraint.class)))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(officialCitation), 0));
        when(retrievalService.toCitations(List.of(officialSourceDocument)))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(officialCitation), 0));

        EnrichmentService enrichmentService = mock(EnrichmentService.class);
        Enrichment lessonEnrichment = emptyEnrichment();
        when(enrichmentService.enrich(anyString(), eq(TEST_JDK_VERSION), eq(List.of(OFFICIAL_SOURCE_TEXT))))
                .thenReturn(lessonEnrichment);

        ChatService chatService = mock(ChatService.class);
        StructuredPrompt structuredPrompt = StructuredPrompt.fromRawPrompt("guided", 1);
        when(chatService.buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()), eq(USER_QUESTION), eq(List.of(officialSourceDocument)), anyString()))
                .thenReturn(structuredPrompt);

        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, enrichmentService, chatService, systemPromptConfig());

        assertEquals(List.of(officialCitation), guidedLearningService.citationsForLesson(LESSON_SLUG));
        assertEquals(lessonEnrichment, guidedLearningService.enrichmentForLesson(LESSON_SLUG));
        GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                guidedLearningService.buildStructuredGuidedPromptWithContext(List.of(), LESSON_SLUG, USER_QUESTION);
        assertEquals(List.of(officialSourceDocument), promptOutcome.lessonContextDocuments());
        assertEquals(
                List.of(officialCitation),
                guidedLearningService
                        .citationOutcomeForContextDocuments(promptOutcome.lessonContextDocuments())
                        .citations());

        ArgumentCaptor<RetrievalConstraint> retrievalConstraintCaptor =
                ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService, org.mockito.Mockito.times(2))
                .retrieve(anyString(), retrievalConstraintCaptor.capture());
        verify(retrievalService).discoverCitations(anyString(), retrievalConstraintCaptor.capture());
        for (RetrievalConstraint guidedConstraint : retrievalConstraintCaptor.getAllValues()) {
            assertEquals("official", guidedConstraint.sourceKind());
            assertEquals(guidedLesson.getDocSet(), guidedConstraint.docSet());
        }
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()),
                        eq(USER_QUESTION),
                        eq(List.of(officialSourceDocument)),
                        guidanceCaptor.capture());
        assertTrue(guidanceCaptor.getValue().contains(guidedLesson.getTechnology()));
        for (String allowedDocSet : guidedLesson.getDocSet()) {
            assertTrue(guidanceCaptor.getValue().contains(allowedDocSet));
        }
    }

    @Test
    void guidedLoopsPromptEmbedsTheCanonicalJava25CompactSourceLesson() throws IOException {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class)))
                .thenReturn(List.of());
        ChatService chatService = mock(ChatService.class);
        when(chatService.buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), anyString()))
                .thenReturn(StructuredPrompt.fromRawPrompt("guided loops", 1));

        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, systemPromptConfig());

        guidedLearningService.buildStructuredGuidedPromptWithContext(List.of(), LOOPS_LESSON_SLUG, USER_QUESTION);

        String canonicalLoopsMarkdown = readCuratedLessonMarkdown(LOOPS_LESSON_SLUG);
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()), eq(USER_QUESTION), eq(List.of()), guidanceCaptor.capture());
        assertTrue(canonicalLoopsMarkdown.contains("void main()"));
        assertTrue(canonicalLoopsMarkdown.contains("IO.println"));
        assertTrue(guidanceCaptor.getValue().contains(canonicalLoopsMarkdown));
        assertTrue(guidanceCaptor.getValue().contains("Java 25 compact source form"));
        assertTrue(
                guidanceCaptor.getValue().contains("class-style source code when the learner explicitly requests it"));
    }

    @Test
    void guidedKotlinPromptFollowsCanonicalContentWithoutJavaCompactSyntaxGuidance() throws IOException {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class)))
                .thenReturn(List.of());
        ChatService chatService = mock(ChatService.class);
        when(chatService.buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), anyString()))
                .thenReturn(StructuredPrompt.fromRawPrompt("guided kotlin", 1));

        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, systemPromptConfig());

        guidedLearningService.buildStructuredGuidedPromptWithContext(List.of(), KOTLIN_LESSON_SLUG, USER_QUESTION);

        String canonicalKotlinMarkdown = readCuratedLessonMarkdown(KOTLIN_LESSON_SLUG);
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()), eq(USER_QUESTION), eq(List.of()), guidanceCaptor.capture());
        assertTrue(guidanceCaptor.getValue().contains(canonicalKotlinMarkdown));
        assertFalse(guidanceCaptor.getValue().contains("Java 25 compact source form"));
    }

    @Test
    void unknownAndBlankLessonsDoNotReachRetrievalOrPromptConstruction() {
        GuidedTOCProvider tocProvider = mock(GuidedTOCProvider.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        EnrichmentService enrichmentService = mock(EnrichmentService.class);
        ChatService chatService = mock(ChatService.class);
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, enrichmentService, chatService, systemPromptConfig());

        for (String invalidLessonSlug : List.of(UNKNOWN_LESSON_SLUG, "")) {
            assertThrows(
                    NoSuchElementException.class, () -> guidedLearningService.citationsForLesson(invalidLessonSlug));
            assertThrows(
                    NoSuchElementException.class, () -> guidedLearningService.enrichmentForLesson(invalidLessonSlug));
            assertThrows(
                    NoSuchElementException.class,
                    () -> guidedLearningService.buildStructuredGuidedPromptWithContext(
                            List.of(), invalidLessonSlug, USER_QUESTION));
        }

        verifyNoInteractions(retrievalService, enrichmentService, chatService);
    }

    @Test
    void preservesCitationConversionFailuresForGuidedStreamingCallers() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        Document officialSourceDocument = Document.builder()
                .id("official-source")
                .text(OFFICIAL_SOURCE_TEXT)
                .build();
        RetrievalService.CitationOutcome expectedCitationOutcome = new RetrievalService.CitationOutcome(List.of(), 1);
        when(retrievalService.toCitations(List.of(officialSourceDocument))).thenReturn(expectedCitationOutcome);

        GuidedLearningService guidedLearningService = guidedLearningService(
                mock(GuidedTOCProvider.class),
                retrievalService,
                mock(EnrichmentService.class),
                mock(ChatService.class),
                systemPromptConfig());

        RetrievalService.CitationOutcome actualCitationOutcome =
                guidedLearningService.citationOutcomeForContextDocuments(List.of(officialSourceDocument));

        assertEquals(expectedCitationOutcome, actualCitationOutcome);
    }

    private static GuidedLearningService guidedLearningService(
            GuidedTOCProvider tocProvider,
            RetrievalService retrievalService,
            EnrichmentService enrichmentService,
            ChatService chatService,
            SystemPromptConfig systemPromptConfig) {
        return new GuidedLearningService(
                tocProvider, retrievalService, enrichmentService, chatService, systemPromptConfig, TEST_JDK_VERSION);
    }

    private GuidedLesson guidedLesson() {
        return new GuidedTOCProvider(objectMapper).findBySlug(LESSON_SLUG).orElseThrow();
    }

    private static Document officialSourceDocument(GuidedLesson guidedLesson) {
        return Document.builder()
                .id("official-string-source")
                .text(OFFICIAL_SOURCE_TEXT)
                .metadata("url", officialSourceUrl(guidedLesson))
                .metadata("title", "Strings")
                .metadata("sourceKind", "official")
                .metadata("docSet", guidedLesson.getDocSet().getFirst())
                .metadata("docType", "tutorial")
                .build();
    }

    private static String officialSourceUrl(GuidedLesson guidedLesson) {
        String sourceDocSet = guidedLesson.getDocSet().getFirst();
        return DocsSourceRegistry.documentationSources().stream()
                        .filter(documentationSource ->
                                documentationSource.docSet().equals(sourceDocSet))
                        .findFirst()
                        .orElseThrow()
                        .citationBaseUrl()
                + "strings/";
    }

    private static Enrichment emptyEnrichment() {
        Enrichment lessonEnrichment = new Enrichment();
        lessonEnrichment.setJdkVersion(TEST_JDK_VERSION);
        lessonEnrichment.setHints(List.of());
        lessonEnrichment.setReminders(List.of());
        lessonEnrichment.setBackground(List.of());
        return lessonEnrichment;
    }

    private static String readCuratedLessonMarkdown(String lessonSlug) throws IOException {
        String lessonResourcePath = CURATED_LESSON_RESOURCE_DIRECTORY + lessonSlug + CURATED_LESSON_FILE_SUFFIX;
        InputStream lessonStream =
                GuidedLearningServiceCitationTest.class.getClassLoader().getResourceAsStream(lessonResourcePath);
        if (lessonStream == null) {
            throw new IllegalStateException(
                    "Curated lesson resource is absent from the classpath: " + lessonResourcePath);
        }
        try (lessonStream) {
            return new String(lessonStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SystemPromptConfig systemPromptConfig() {
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        when(systemPromptConfig.getMarkerUsagePrompt()).thenReturn("{{hint:Text here}}");
        when(systemPromptConfig.getGuidedLearningPrompt()).thenReturn("Teach this lesson progressively.");
        when(systemPromptConfig.buildFullPrompt(anyString(), anyString()))
                .thenAnswer(promptInvocation -> promptInvocation.getArgument(0));
        return systemPromptConfig;
    }
}
