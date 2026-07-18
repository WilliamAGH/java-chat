package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.model.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies chat citation lookup remains isolated from answer-context retrieval and reranking. */
class ChatServiceTest {

    private static final String CITATION_QUERY = "Java records";

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
}
