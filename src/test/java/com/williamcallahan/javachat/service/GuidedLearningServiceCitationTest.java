package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.CurrentQuerySegment;
import com.williamcallahan.javachat.domain.prompt.PromptSegmentPriority;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.prompt.SystemSegment;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
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
    private static final String LARGEST_CURATED_LESSON_SLUG = "spring-boot-vs-quarkus";
    private static final String CURATED_LESSON_RESOURCE_DIRECTORY = "guided/lessons/";
    private static final String CURATED_LESSON_FILE_SUFFIX = ".md";
    private static final String CURATED_LESSON_IMMUTABILITY_HEADER = "[AUTHORITATIVE IMMUTABLE LESSON REFERENCE]";
    private static final int GPT54_INPUT_TOKEN_LIMIT = 8_000;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void guidedEnrichmentAndPromptFlowsUseTheLessonOfficialDocSetConstraint() throws IOException {
        GuidedLesson guidedLesson = guidedLesson();
        String canonicalStringsMarkdown = readCuratedLessonMarkdown(LESSON_SLUG);
        GuidedTOCProvider tocProvider = mock(GuidedTOCProvider.class);
        when(tocProvider.findBySlug(LESSON_SLUG)).thenReturn(Optional.of(guidedLesson));

        Document officialSourceDocument = officialSourceDocument(guidedLesson);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(List.of(officialSourceDocument));
        Citation officialCitation = new Citation(officialSourceUrl(guidedLesson), "Strings", "", "substring");
        when(retrievalService.toCitationsForRetainedContext(
                        List.of(officialSourceDocument), List.of(officialSourceDocument.getId())))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(officialCitation), 0));

        EnrichmentService enrichmentService = mock(EnrichmentService.class);
        Enrichment lessonEnrichment = emptyEnrichment();
        when(enrichmentService.enrich(anyString(), eq(TEST_JDK_VERSION), eq(List.of(OFFICIAL_SOURCE_TEXT))))
                .thenReturn(lessonEnrichment);

        ChatService chatService = mock(ChatService.class);
        StructuredPrompt structuredPrompt = new StructuredPrompt(
                new SystemSegment("guided", 1),
                List.of(
                        new ContextDocumentSegment(
                                1, "curated-lesson:" + LESSON_SLUG + "#section-1", "", canonicalStringsMarkdown, 1),
                        new ContextDocumentSegment(
                                2,
                                officialSourceDocument.getId(),
                                officialSourceUrl(guidedLesson),
                                OFFICIAL_SOURCE_TEXT,
                                1)),
                List.of(),
                new CurrentQuerySegment(USER_QUESTION, 1));
        when(chatService.buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()), eq(USER_QUESTION), any(), anyString()))
                .thenReturn(structuredPrompt);

        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, enrichmentService, chatService, systemPromptConfig());

        assertEquals(lessonEnrichment, guidedLearningService.enrichmentForLesson(LESSON_SLUG));
        GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                guidedLearningService.buildStructuredGuidedPromptWithContext(List.of(), LESSON_SLUG, USER_QUESTION);
        assertEquals(List.of(officialSourceDocument), promptOutcome.lessonContextDocuments());
        assertEquals(
                PromptSegmentPriority.HIGH,
                promptOutcome.structuredPrompt().contextDocuments().getFirst().priority());
        assertEquals(
                PromptSegmentPriority.LOW,
                promptOutcome.structuredPrompt().contextDocuments().get(1).priority());
        assertEquals(
                List.of(officialCitation),
                guidedLearningService
                        .citationOutcomeForRetainedContext(promptOutcome, List.of(officialSourceDocument.getId()))
                        .citations());

        ArgumentCaptor<RetrievalConstraint> retrievalConstraintCaptor =
                ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService, org.mockito.Mockito.times(2))
                .retrieve(anyString(), retrievalConstraintCaptor.capture(), any(), anyLong());
        for (RetrievalConstraint guidedConstraint : retrievalConstraintCaptor.getAllValues()) {
            assertEquals("official", guidedConstraint.sourceKind());
            assertEquals(guidedLesson.getDocSet(), guidedConstraint.docSet());
        }
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()),
                        eq(USER_QUESTION),
                        org.mockito.ArgumentMatchers.argThat(promptContextDocuments -> {
                            return hasCanonicalLessonSections(
                                    promptContextDocuments,
                                    LESSON_SLUG,
                                    canonicalStringsMarkdown,
                                    List.of(officialSourceDocument));
                        }),
                        guidanceCaptor.capture());
        assertTrue(guidanceCaptor.getValue().contains(guidedLesson.getTechnology()));
        for (String allowedDocSet : guidedLesson.getDocSet()) {
            assertTrue(guidanceCaptor.getValue().contains(allowedDocSet));
        }
        assertFalse(guidanceCaptor.getValue().contains("{{example:"));
        assertFalse(guidanceCaptor.getValue().contains("## Work with text using `String`"));
        assertTrue(guidanceCaptor.getValue().contains("copy one complete fenced example byte-for-byte"));
        assertTrue(guidanceCaptor.getValue().contains("Do not transcribe it from memory"));
    }

    @Test
    void modernJavaPatternMatchingCitationsMatchCanonicalMarkdownLinks() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        GuidedLearningService guidedLearningService = guidedLearningService(
                new GuidedTOCProvider(objectMapper),
                retrievalService,
                mock(EnrichmentService.class),
                mock(ChatService.class),
                systemPromptConfig());

        List<Citation> lessonCitations = guidedLearningService.citationsForLesson("modern-java-pattern-matching");

        assertEquals(3, lessonCitations.size());
        assertCitation(
                lessonCitations.get(0),
                "https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html#jls-6.3.1.5",
                "JLS 6.3.1.5: Scope for Pattern Variables");
        assertCitation(
                lessonCitations.get(1),
                "https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.30",
                "JLS 14.30: Patterns");
        assertCitation(
                lessonCitations.get(2),
                "https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.11",
                "JLS 14.11: switch");
        verifyNoInteractions(retrievalService);
    }

    @Test
    void everyGuidedLessonPublishesCanonicalHttpsCitations() {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider,
                retrievalService,
                mock(EnrichmentService.class),
                mock(ChatService.class),
                systemPromptConfig());

        for (GuidedLesson guidedLesson : tocProvider.getTOC()) {
            List<Citation> lessonCitations = guidedLearningService.citationsForLesson(guidedLesson.getSlug());
            assertFalse(
                    lessonCitations.isEmpty(),
                    () -> "Guided lesson has no canonical citation: " + guidedLesson.getSlug());
            assertTrue(
                    lessonCitations.stream()
                            .map(Citation::getUrl)
                            .allMatch(citationUrl -> citationUrl.startsWith("https://")),
                    () -> "Guided lesson has a non-HTTPS citation: " + guidedLesson.getSlug());
        }

        verifyNoInteractions(retrievalService);
    }

    @Test
    void guidedLoopsPromptSuppliesTheCanonicalJava25CompactSourceLessonAsContext() throws IOException {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
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
                        eq(List.of()),
                        eq(USER_QUESTION),
                        org.mockito.ArgumentMatchers.argThat(promptContextDocuments -> hasCanonicalLessonSections(
                                promptContextDocuments, LOOPS_LESSON_SLUG, canonicalLoopsMarkdown, List.of())),
                        guidanceCaptor.capture());
        assertTrue(canonicalLoopsMarkdown.contains("void main()"));
        assertTrue(canonicalLoopsMarkdown.contains("IO.println"));
        assertFalse(guidanceCaptor.getValue().contains(canonicalLoopsMarkdown));
        assertTrue(guidanceCaptor.getValue().contains("Java 25 compact source form"));
        assertTrue(
                guidanceCaptor.getValue().contains("class-style source code when the learner explicitly requests it"));
    }

    @Test
    void guidedKotlinPromptFollowsCanonicalContentWithoutJavaCompactSyntaxGuidance() throws IOException {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
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
                        eq(List.of()),
                        eq(USER_QUESTION),
                        org.mockito.ArgumentMatchers.argThat(promptContextDocuments -> hasCanonicalLessonSections(
                                promptContextDocuments, KOTLIN_LESSON_SLUG, canonicalKotlinMarkdown, List.of())),
                        guidanceCaptor.capture());
        assertFalse(guidanceCaptor.getValue().contains(canonicalKotlinMarkdown));
        assertFalse(guidanceCaptor.getValue().contains("Java 25 compact source form"));
    }

    @Test
    void guidedJavaComparisonUsesBothRequestedApiScopesInRetrievalAndGuidance() {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        Document java21Document = Document.builder()
                .id("java-21-string")
                .text("Java 21 String documentation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, "21")
                .build();
        Document java26Document = Document.builder()
                .id("java-26-string")
                .text("Java 26 String documentation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, "26")
                .build();
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(List.of(java21Document, java26Document));
        ChatService chatService = mock(ChatService.class);
        when(chatService.buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), anyString()))
                .thenReturn(StructuredPrompt.fromRawPrompt("guided comparison", 1));
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, systemPromptConfig());
        String comparisonQuestion = "Compare Java 21 and Java 26 String methods";

        GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                guidedLearningService.buildStructuredGuidedPromptWithContext(
                        List.of(), LESSON_SLUG, comparisonQuestion);

        assertEquals(List.of(java21Document, java26Document), promptOutcome.lessonContextDocuments());
        ArgumentCaptor<RetrievalConstraint> retrievalConstraintCaptor =
                ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService).retrieve(anyString(), retrievalConstraintCaptor.capture(), any(), anyLong());
        assertEquals(
                List.of("java/java21-complete", "java/java26-complete"),
                retrievalConstraintCaptor.getValue().docSet());
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(
                        eq(List.of()),
                        eq(comparisonQuestion),
                        org.mockito.ArgumentMatchers.argThat(promptContextDocuments -> hasCanonicalLessonSections(
                                promptContextDocuments,
                                LESSON_SLUG,
                                readCuratedLessonMarkdownUnchecked(LESSON_SLUG),
                                List.of(java21Document, java26Document))),
                        guidanceCaptor.capture());
        assertTrue(guidanceCaptor.getValue().contains("java/java21-complete"));
        assertTrue(guidanceCaptor.getValue().contains("java/java26-complete"));
        assertFalse(guidanceCaptor.getValue().contains("java/java25-complete"));
        assertFalse(guidanceCaptor.getValue().contains("dev-java"));
        assertTrue(guidanceCaptor.getValue().contains("for pedagogical structure only"));
        assertFalse(guidanceCaptor.getValue().contains("copy one complete fenced example byte-for-byte"));
        assertFalse(guidanceCaptor.getValue().contains("Java 25 compact source form"));
    }

    @Test
    void missingJavaReleaseUsesNearestOlderAndNewerDocumentation() {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(List.of());
        ChatService chatService = mock(ChatService.class);
        when(chatService.buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), anyString()))
                .thenReturn(StructuredPrompt.fromRawPrompt("adjacent release evidence", 1));
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, systemPromptConfig());

        guidedLearningService.buildStructuredGuidedPromptWithContext(
                List.of(), LESSON_SLUG, "How does this work in Java 22?");

        ArgumentCaptor<RetrievalConstraint> retrievalConstraintCaptor =
                ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService).retrieve(anyString(), retrievalConstraintCaptor.capture(), any(), anyLong());
        assertEquals(
                List.of("java/java21-complete", "java/java25-complete"),
                retrievalConstraintCaptor.getValue().docSet());
        ArgumentCaptor<String> guidanceCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService)
                .buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), guidanceCaptor.capture());
        assertTrue(guidanceCaptor.getValue().contains("clearly labeled version delta"));
        assertTrue(guidanceCaptor.getValue().contains("adjacent same-family evidence"));
    }

    @Test
    void nonJavaLessonKeepsItsOwnScopeForAnOffTopicJavaVersionQuestion() {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(List.of());
        ChatService chatService = mock(ChatService.class);
        when(chatService.buildStructuredPromptWithContextAndGuidance(any(), anyString(), any(), anyString()))
                .thenReturn(StructuredPrompt.fromRawPrompt("guided redirect", 1));
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, systemPromptConfig());
        String offTopicQuestion = "What changed in Java 21?";

        guidedLearningService.buildStructuredGuidedPromptWithContext(List.of(), KOTLIN_LESSON_SLUG, offTopicQuestion);

        ArgumentCaptor<String> retrievalQueryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RetrievalConstraint> retrievalConstraintCaptor =
                ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService)
                .retrieve(retrievalQueryCaptor.capture(), retrievalConstraintCaptor.capture(), any(), anyLong());
        assertTrue(retrievalQueryCaptor.getValue().contains(offTopicQuestion));
        assertEquals(List.of("kotlin"), retrievalConstraintCaptor.getValue().docSet());
        assertTrue(retrievalConstraintCaptor.getValue().docVersions().isEmpty());
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
        when(retrievalService.toCitationsForRetainedContext(
                        List.of(officialSourceDocument), List.of(officialSourceDocument.getId())))
                .thenReturn(expectedCitationOutcome);

        GuidedLearningService guidedLearningService = guidedLearningService(
                mock(GuidedTOCProvider.class),
                retrievalService,
                mock(EnrichmentService.class),
                mock(ChatService.class),
                systemPromptConfig());

        RetrievalService.CitationOutcome actualCitationOutcome =
                guidedLearningService.citationOutcomeForRetainedContext(
                        new GuidedLearningService.GuidedChatPromptOutcome(
                                StructuredPrompt.fromRawPrompt("guided", 1), List.of(officialSourceDocument)),
                        List.of(officialSourceDocument.getId()));

        assertEquals(expectedCitationOutcome, actualCitationOutcome);
    }

    @Test
    void largestPackagedLessonRetainsAuthoritativeSectionsUnderGpt54InputBudget() throws IOException {
        GuidedTOCProvider tocProvider = new GuidedTOCProvider(objectMapper);
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(List.of());
        SystemPromptConfig promptConfig = systemPromptConfig();
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class), retrievalService, promptConfig, new AppProperties());
        GuidedLearningService guidedLearningService = guidedLearningService(
                tocProvider, retrievalService, mock(EnrichmentService.class), chatService, promptConfig);

        GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                guidedLearningService.buildStructuredGuidedPromptWithContext(
                        List.of(), LARGEST_CURATED_LESSON_SLUG, USER_QUESTION);
        List<ContextDocumentSegment> authoritativeLessonSections =
                promptOutcome.structuredPrompt().contextDocuments();

        assertTrue(authoritativeLessonSections.size() > 1);
        assertTrue(authoritativeLessonSections.stream()
                .allMatch(contextDocument -> contextDocument.priority() == PromptSegmentPriority.HIGH));
        assertEquals(
                authoritativeLessonSections.size(),
                authoritativeLessonSections.stream()
                        .map(ContextDocumentSegment::documentId)
                        .distinct()
                        .count());
        PromptTruncator.TruncatedPrompt truncationOutcome =
                new PromptTruncator().truncate(promptOutcome.structuredPrompt(), GPT54_INPUT_TOKEN_LIMIT);
        assertTrue(truncationOutcome.wasTruncated());
        assertTrue(truncationOutcome.contextDocumentCount() > 0);
        assertTrue(truncationOutcome.contextDocumentCount() < authoritativeLessonSections.size());
        assertTrue(truncationOutcome.prompt().contextDocuments().stream()
                .allMatch(contextDocument -> contextDocument.priority() == PromptSegmentPriority.HIGH));
    }

    private static GuidedLearningService guidedLearningService(
            GuidedTOCProvider tocProvider,
            RetrievalService retrievalService,
            EnrichmentService enrichmentService,
            ChatService chatService,
            SystemPromptConfig systemPromptConfig) {
        return new GuidedLearningService(
                tocProvider,
                retrievalService,
                enrichmentService,
                chatService,
                systemPromptConfig,
                new MarkdownService(new UnifiedMarkdownService()),
                TEST_JDK_VERSION);
    }

    private static void assertCitation(Citation citation, String expectedUrl, String expectedTitle) {
        assertEquals(expectedUrl, citation.getUrl());
        assertEquals(expectedTitle, citation.getTitle());
        assertEquals("", citation.getAnchor());
        assertEquals("", citation.getSnippet());
    }

    private GuidedLesson guidedLesson() {
        return new GuidedTOCProvider(objectMapper).findBySlug(LESSON_SLUG).orElseThrow();
    }

    private static Document officialSourceDocument(GuidedLesson guidedLesson) {
        return Document.builder()
                .id("official-string-source")
                .text(OFFICIAL_SOURCE_TEXT)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, officialSourceUrl(guidedLesson))
                .metadata(QdrantPayloadFieldSchema.TITLE_FIELD, "Strings")
                .metadata(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD, "official")
                .metadata(
                        QdrantPayloadFieldSchema.DOC_SET_FIELD,
                        guidedLesson.getDocSet().getFirst())
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, "tutorial")
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

    private static boolean hasCanonicalLessonSections(
            List<Document> promptContextDocuments,
            String lessonSlug,
            String canonicalLessonMarkdown,
            List<Document> retrievedDocuments) {
        int lessonSectionCount = promptContextDocuments.size() - retrievedDocuments.size();
        if (lessonSectionCount <= 1
                || !promptContextDocuments
                        .subList(lessonSectionCount, promptContextDocuments.size())
                        .equals(retrievedDocuments)) {
            return false;
        }
        String lessonSectionIdPrefix = "curated-lesson:" + lessonSlug + "#section-";
        StringBuilder reconstructedLessonMarkdown = new StringBuilder(canonicalLessonMarkdown.length());
        for (int sectionIndex = 0; sectionIndex < lessonSectionCount; sectionIndex++) {
            Document lessonSectionDocument = promptContextDocuments.get(sectionIndex);
            if (!lessonSectionDocument.getId().equals(lessonSectionIdPrefix + (sectionIndex + 1))) {
                return false;
            }
            String lessonSectionText =
                    Objects.requireNonNull(lessonSectionDocument.getText(), "curated lesson section text");
            if (sectionIndex == 0) {
                String immutableHeaderPrefix = CURATED_LESSON_IMMUTABILITY_HEADER + "\n";
                if (!lessonSectionText.startsWith(immutableHeaderPrefix)) {
                    return false;
                }
                int lessonMarkdownStart = lessonSectionText.indexOf("\n\n") + 2;
                if (lessonMarkdownStart < 2) {
                    return false;
                }
                lessonSectionText = lessonSectionText.substring(lessonMarkdownStart);
            } else if (lessonSectionText.contains(CURATED_LESSON_IMMUTABILITY_HEADER)) {
                return false;
            }
            reconstructedLessonMarkdown.append(lessonSectionText);
        }
        return reconstructedLessonMarkdown.toString().equals(canonicalLessonMarkdown);
    }

    private static String readCuratedLessonMarkdownUnchecked(String lessonSlug) {
        try {
            return readCuratedLessonMarkdown(lessonSlug);
        } catch (IOException lessonReadFailure) {
            throw new UncheckedIOException(lessonReadFailure);
        }
    }

    private static SystemPromptConfig systemPromptConfig() {
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        when(systemPromptConfig.getCoreSystemPrompt()).thenReturn("Teach Java from authoritative sources.");
        when(systemPromptConfig.getGuidedLearningPrompt()).thenReturn("Teach this lesson progressively.");
        when(systemPromptConfig.buildFullPrompt(anyString(), anyString()))
                .thenAnswer(promptInvocation -> promptInvocation.getArgument(0));
        return systemPromptConfig;
    }
}
