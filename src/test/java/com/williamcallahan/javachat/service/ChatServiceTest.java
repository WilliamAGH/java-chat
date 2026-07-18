package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

/** Verifies chat answer context and static citations remain grounded in official documentation. */
class ChatServiceTest {

    private static final String CITATION_QUERY = "Java records";
    private static final String VERSIONED_CONTEXT_QUERY = "Java 17 List.of";
    private static final String NON_TOKEN_CONSTRAINED_MODEL = "gpt-4.1";

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
    void versionedStructuredPromptUsesOfficialContextForTheDefaultTokenConstrainedModel() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        when(systemPromptConfig.getCoreSystemPrompt()).thenReturn("You are a Java tutor.");
        when(retrievalService.retrieveWithLimitOutcome(
                        eq(VERSIONED_CONTEXT_QUERY),
                        eq(ModelConfiguration.RAG_LIMIT_CONSTRAINED),
                        eq(ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED),
                        any(RetrievalConstraint.class)))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(), List.of()));
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class), retrievalService, systemPromptConfig, new AppProperties());

        chatService.buildStructuredPromptWithContextOutcome(
                List.of(), VERSIONED_CONTEXT_QUERY, ModelConfiguration.DEFAULT_MODEL);

        ArgumentCaptor<RetrievalConstraint> constraintCaptor = ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService)
                .retrieveWithLimitOutcome(
                        eq(VERSIONED_CONTEXT_QUERY),
                        eq(ModelConfiguration.RAG_LIMIT_CONSTRAINED),
                        eq(ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED),
                        constraintCaptor.capture());
        RetrievalConstraint answerContextConstraint = constraintCaptor.getValue();
        assertEquals("official", answerContextConstraint.sourceKind());
        assertEquals(DocsSourceRegistry.officialDocumentationSourceIdentities(), answerContextConstraint.docSet());
        verify(retrievalService, never()).discoverCitations(anyString(), any(RetrievalConstraint.class));
    }

    @Test
    void versionedStructuredPromptUsesOfficialContextForNonTokenConstrainedModels() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        SystemPromptConfig systemPromptConfig = mock(SystemPromptConfig.class);
        when(systemPromptConfig.getCoreSystemPrompt()).thenReturn("You are a Java tutor.");
        when(retrievalService.retrieveOutcome(eq(VERSIONED_CONTEXT_QUERY), any(RetrievalConstraint.class)))
                .thenReturn(new RetrievalService.RetrievalOutcome(List.of(), List.of()));
        ChatService chatService = new ChatService(
                mock(OpenAIStreamingService.class), retrievalService, systemPromptConfig, new AppProperties());

        chatService.buildStructuredPromptWithContextOutcome(
                List.of(), VERSIONED_CONTEXT_QUERY, NON_TOKEN_CONSTRAINED_MODEL);

        ArgumentCaptor<RetrievalConstraint> constraintCaptor = ArgumentCaptor.forClass(RetrievalConstraint.class);
        verify(retrievalService).retrieveOutcome(eq(VERSIONED_CONTEXT_QUERY), constraintCaptor.capture());
        RetrievalConstraint answerContextConstraint = constraintCaptor.getValue();
        assertEquals("official", answerContextConstraint.sourceKind());
        assertEquals(DocsSourceRegistry.officialDocumentationSourceIdentities(), answerContextConstraint.docSet());
        verify(retrievalService, never()).discoverCitations(anyString(), any(RetrievalConstraint.class));
    }
}
