package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Verifies retrieval outcome behavior around hybrid-search notices and strict failures.
 */
class RetrievalServiceTest {

    @Test
    void propagatesHybridSearchNotices() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        DocumentFactory documentFactory = mock(DocumentFactory.class);
        AppProperties appProperties = new AppProperties();
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, documentFactory);

        Document candidateDocument =
                Document.builder().id("candidate-1").text("Stream tutorial").build();
        candidateDocument.getMetadata().put("url", "https://docs.example.com/java/streams");
        candidateDocument.getMetadata().put("hash", "hash-1");

        HybridSearchService.HybridSearchNotice searchNotice =
                new HybridSearchService.HybridSearchNotice("Partial retrieval failure", "Timeout: java-docs");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(candidateDocument), List.of(searchNotice)));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(candidateDocument));

        RetrievalService.RetrievalOutcome retrievalOutcome = retrievalService.retrieveOutcome("Java stream basics");

        assertEquals(1, retrievalOutcome.documents().size());
        assertEquals(1, retrievalOutcome.notices().size());
        assertEquals(
                "Partial retrieval failure", retrievalOutcome.notices().get(0).summary());
    }

    @Test
    void propagatesStrictHybridSearchFailure() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        DocumentFactory documentFactory = mock(DocumentFactory.class);
        AppProperties appProperties = new AppProperties();
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, documentFactory);

        HybridSearchPartialFailureException.CollectionSearchFailure collectionFailure =
                new HybridSearchPartialFailureException.CollectionSearchFailure("java-docs", "Timeout", "5s");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class)))
                .thenThrow(new HybridSearchPartialFailureException("collection failure", List.of(collectionFailure)));

        assertThrows(
                HybridSearchPartialFailureException.class,
                () -> retrievalService.retrieveOutcome("Java stream basics"));
    }

    @Test
    void toCitationsReportsFailedConversionCountAndKeepsValidCitations() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        DocumentFactory documentFactory = mock(DocumentFactory.class);
        AppProperties appProperties = new AppProperties();
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, documentFactory);

        Document malformedDocument = Document.builder()
                .id("malformed-doc")
                .text("Malformed metadata")
                .build();
        malformedDocument.getMetadata().put("url", new BrokenUrlValue());
        malformedDocument.getMetadata().put("title", "Broken citation");

        Document validDocument =
                Document.builder().id("valid-doc").text("Valid snippet").build();
        validDocument.getMetadata().put("url", "https://docs.oracle.com/javase/8/docs/api/java/lang/String.html");
        validDocument.getMetadata().put("title", "String");

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.toCitations(List.of(malformedDocument, validDocument));

        assertEquals(1, citationOutcome.citations().size());
        assertEquals(1, citationOutcome.failedConversionCount());
        assertEquals("String", citationOutcome.citations().get(0).getTitle());
        assertTrue(citationOutcome.citations().get(0).getUrl().contains("docs.oracle.com"));
    }

    /**
     * Simulates malformed metadata values whose string conversion fails at runtime.
     */
    private static final class BrokenUrlValue {
        @Override
        public String toString() {
            throw new IllegalStateException("broken url value");
        }
    }
}
