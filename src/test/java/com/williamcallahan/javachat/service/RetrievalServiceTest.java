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
import com.williamcallahan.javachat.config.DocsSourceRegistry;
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
    void preservesDistinctSamePageChunksForRerankingAndDeduplicatesTheirCitations() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        DocumentFactory documentFactory = mock(DocumentFactory.class);
        AppProperties appProperties = new AppProperties();
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, documentFactory);
        String javaApiBaseUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl();
        String stringJavadocUrl = javaApiBaseUrl + "java.base/java/lang/String.html";

        Document urlOnlyDocument = Document.builder()
                .id("url-only")
                .text("URL-only candidate")
                .metadata("url", "https://example.org/java/reference")
                .build();
        Document canonicalUrlDuplicateWithoutHash = Document.builder()
                .id("url-only-duplicate")
                .text("URL-only duplicate candidate")
                .metadata("url", "https://example.org//java/reference")
                .build();
        Document firstJavadocChunk = Document.builder()
                .id("first-javadoc-chunk")
                .text("First Javadoc chunk")
                .metadata("url", stringJavadocUrl)
                .metadata("hash", "first-content-hash")
                .build();
        Document secondJavadocChunkWithDistinctHash = Document.builder()
                .id("second-javadoc-chunk")
                .text("Second Javadoc chunk")
                .metadata("url", stringJavadocUrl + "#assert(...)")
                .metadata("hash", "second-content-hash")
                .build();
        Document sameContentHashWithDifferentUrl = Document.builder()
                .id("same-content-hash")
                .text("Duplicate content under another URL")
                .metadata("url", javaApiBaseUrl + "java.base/java/lang/Object.html")
                .metadata("hash", "first-content-hash")
                .build();
        List<Document> retrievalCandidates = List.of(
                urlOnlyDocument,
                canonicalUrlDuplicateWithoutHash,
                firstJavadocChunk,
                secondJavadocChunkWithDistinctHash,
                sameContentHashWithDifferentUrl);
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class)))
                .thenReturn(new HybridSearchService.SearchOutcome(retrievalCandidates, List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenAnswer(rerankerInvocation -> {
            List<Document> deduplicatedCandidates = rerankerInvocation.getArgument(1);
            return deduplicatedCandidates;
        });

        RetrievalService.RetrievalOutcome retrievalOutcome = retrievalService.retrieveOutcome("Java string basics");
        RetrievalService.CitationOutcome citationOutcome = retrievalService.toCitations(retrievalOutcome.documents());

        assertEquals(
                List.of(urlOnlyDocument, firstJavadocChunk, secondJavadocChunkWithDistinctHash),
                retrievalOutcome.documents());
        assertEquals(2, citationOutcome.citations().size());
        assertEquals(stringJavadocUrl, citationOutcome.citations().get(1).getUrl());
        assertEquals("First Javadoc chunk", citationOutcome.citations().get(1).getSnippet());
        assertEquals(0, citationOutcome.failedConversionCount());
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
