package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.model.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

/** Verifies chat answer context and static citations remain grounded in official documentation. */
class ChatServiceTest {

    private static final String CITATION_QUERY = "Java records";
    private static final String VERSIONED_CONTEXT_QUERY = "Java 17 List.of";

    @Test
    void citationsUseSparseDiscoveryConstrainedByEveryCanonicalOfficialSourceIdentity() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class),
                retrievalService,
                mock(SystemPromptConfig.class),
                new AppProperties());
        Citation expectedCitation = new Citation("https://docs.example.test/Record.html", "Record", "", "Record API");
        when(retrievalService.discoverCitations(eq(CITATION_QUERY), any(RetrievalConstraint.class)))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(expectedCitation), 0));

        List<Citation> citations = chatService.citationsFor(CITATION_QUERY);

        ArgumentCaptor<RetrievalConstraint> constraintCaptor = ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService).discoverCitations(eq(CITATION_QUERY), constraintCaptor.capture());
        RetrievalConstraint citationConstraint = constraintCaptor.getValue();
        assertEquals("official", citationConstraint.sourceKind());
        assertEquals(DocsSourceRegistry.officialDocumentationSourceIdentities(), citationConstraint.docSet());
        assertEquals(List.of(expectedCitation), citations);
        verify(retrievalService, never()).retrieve(anyString());
        verify(retrievalService, never()).retrieve(anyString(), any(RetrievalConstraint.class));
        verify(retrievalService, never()).retrieveOutcome(anyString());
        verify(retrievalService, never()).retrieveOutcome(anyString(), any(RetrievalConstraint.class));
    }

    @Test
    void citationsRejectPartialCitationConversionOutcomes() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class),
                retrievalService,
                mock(SystemPromptConfig.class),
                new AppProperties());
        when(retrievalService.discoverCitations(eq(CITATION_QUERY), any(RetrievalConstraint.class)))
                .thenReturn(new RetrievalService.CitationOutcome(
                        List.of(new Citation("https://docs.example.test/Record.html", "Record", "", "Record API")), 1));

        CitationConversionFailureException conversionFailure =
                assertThrows(CitationConversionFailureException.class, () -> chatService.citationsFor(CITATION_QUERY));

        assertEquals(1, conversionFailure.failedConversionCount());
    }

    @Test
    void versionedStructuredPromptUsesReducedOfficialContextForTheDefaultGpt54Model() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(systemPromptConfig.getCoreSystemPrompt()).thenReturn("You are a Java tutor.");
        when(systemPromptConfig.getVersionEvidencePrompt(any())).thenReturn("");
        when(retrievalService.retrieveWithLimitOutcome(
                        eq(VERSIONED_CONTEXT_QUERY),
                        eq(ModelConfiguration.RAG_LIMIT_CONSTRAINED),
                        eq(ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED),
                        any(RetrievalConstraint.class),
                        any(),
                        anyLong()))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(), List.of()));
        ChatService chatService =
                new ChatService(streamingService, retrievalService, systemPromptConfig, new AppProperties());

        chatService.buildStructuredPromptWithContextOutcome(List.of(), VERSIONED_CONTEXT_QUERY);

        ArgumentCaptor<RetrievalConstraint> constraintCaptor = ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService)
                .retrieveWithLimitOutcome(
                        eq(VERSIONED_CONTEXT_QUERY),
                        eq(ModelConfiguration.RAG_LIMIT_CONSTRAINED),
                        eq(ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED),
                        constraintCaptor.capture(),
                        any(),
                        anyLong());
        RetrievalConstraint answerContextConstraint = constraintCaptor.getValue();
        assertEquals("official", answerContextConstraint.sourceKind());
        assertEquals(DocsSourceRegistry.officialDocumentationSourceIdentities(), answerContextConstraint.docSet());
        verify(retrievalService, never()).discoverCitations(anyString(), any(RetrievalConstraint.class));
    }

    @Test
    void structuredPromptDoesNotInventVersionForUnversionedRetainedContext() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        when(systemPromptConfig.getCoreSystemPrompt()).thenReturn("Use exact source records.");
        when(systemPromptConfig.getVersionEvidencePrompt(any())).thenReturn("");
        Document springReference = Document.builder()
                .id("spring-reference-707")
                .text("Transaction behavior")
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.spring.io/spring-framework/reference/data-access.html")
                .metadata(QdrantPayloadFieldSchema.SOURCE_NAME_FIELD, "Spring Framework Reference")
                .build();
        when(retrievalService.retrieveWithLimitOutcome(
                        eq("Spring 7.0.7 transaction behavior"),
                        eq(ModelConfiguration.RAG_LIMIT_CONSTRAINED),
                        eq(ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED),
                        any(RetrievalConstraint.class),
                        any(),
                        anyLong()))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(springReference), List.of()));
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class), retrievalService, systemPromptConfig, new AppProperties());

        ChatService.StructuredPromptOutcome promptOutcome =
                chatService.buildStructuredPromptWithContextOutcome(List.of(), "Spring 7.0.7 transaction behavior");

        assertEquals(
                "[CTX 1] https://docs.spring.io/spring-framework/reference/data-access.html\n"
                        + "[SOURCE RECORD family=\"Spring Framework Reference\" version=\"unspecified\"]\n"
                        + "Transaction behavior",
                promptOutcome.structuredPrompt().contextDocuments().getFirst().content());
    }

    @Test
    void ordinaryPromptNamesEvidenceForEachRequestedVersionGap() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieveWithLimitOutcome(
                        anyString(), anyInt(), anyInt(), any(RetrievalConstraint.class), any(), anyLong()))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(), List.of()));
        AppProperties appProperties = new AppProperties();
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class),
                retrievalService,
                new SystemPromptConfig(appProperties),
                appProperties);

        ChatService.StructuredPromptOutcome promptOutcome =
                chatService.buildStructuredPromptWithContextOutcome(List.of(), "Compare Java 22 vs Java 26 records");

        String systemPrompt = promptOutcome.structuredPrompt().system().content();
        assertTrue(systemPrompt.contains("requested Java 22; use same-family evidence from Java 21, 24"));
        assertTrue(systemPrompt.contains("requested Java 26; use same-family evidence from Java 25"));
    }
}
