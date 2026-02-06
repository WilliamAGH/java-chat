package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
