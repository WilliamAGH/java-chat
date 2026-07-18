package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static final DocsSourceRegistry.JavaApiDocumentationSource REPRESENTED_JAVA_API_SOURCE =
            DocsSourceRegistry.javaApiDocumentationSources().getFirst();
    private static final List<String> OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES =
            DocsSourceRegistry.officialDocumentationSourceIdentities();

    @Test
    void constrainedRetrievalPassesTheCallerConstraintInstanceToHybridSearch() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(guidedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        retrievalService.retrieveOutcome("Java strings", guidedConstraint);

        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), same(guidedConstraint));
    }

    @Test
    void versionedConstrainedRetrievalCombinesOfficialScopeAndQueryVersionForHybridSearch() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedCombinedConstraint =
                officialDocumentationConstraint.withDocVersion(REPRESENTED_JAVA_API_SOURCE.javaRelease());
        String versionedQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " List.of";
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(expectedCombinedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        retrievalService.retrieveOutcome(versionedQuery, officialDocumentationConstraint);

        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), eq(expectedCombinedConstraint));
        assertEquals(REPRESENTED_JAVA_API_SOURCE.javaRelease(), expectedCombinedConstraint.docVersion());
        assertEquals("official", expectedCombinedConstraint.sourceKind());
        assertEquals(officialDocumentationConstraint.docSet(), expectedCombinedConstraint.docSet());
    }

    @Test
    void citationDiscoveryLimitsAfterFinalUrlAndAnchorDeduplication() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchTopK(4);
        appProperties.getRag().setSearchCitations(2);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        String repeatedCitationUrl = "https://docs.example.test/String.html#substring(int,int)";
        String uniqueCitationUrl = "https://docs.example.test/List.html#get(int)";
        List<Document> citationCandidates = List.of(
                citationCandidateDocument(
                        "first-string-chunk", "First String chunk", "first-hash", repeatedCitationUrl),
                citationCandidateDocument(
                        "second-string-chunk", "Second String chunk", "second-hash", repeatedCitationUrl),
                citationCandidateDocument(
                        "third-string-chunk", "Third String chunk", "third-hash", repeatedCitationUrl),
                citationCandidateDocument("list-chunk", "List chunk", "list-hash", uniqueCitationUrl));
        when(hybridSearchService.searchDocumentationCitationsOutcome(anyString(), eq(4), same(guidedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(citationCandidates, List.of()));

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations("Java strings", guidedConstraint);

        assertEquals(
                List.of("https://docs.example.test/String.html", "https://docs.example.test/List.html"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(
                List.of("substring(int,int)", "get(int)"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getAnchor())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
        verify(hybridSearchService).searchDocumentationCitationsOutcome(anyString(), eq(4), same(guidedConstraint));
        verify(hybridSearchService, never()).searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void citationDiscoveryReranksATypePageBeyondTheFirstThreeCandidatesBeforeFinalLimiting() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchTopK(4);
        appProperties.getRag().setSearchCitations(3);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        String citationQuery = "What does List.of return?";
        String listPageUrl = javaApiPageUrl("java.util", "List.html");
        List<Document> qdrantCandidates = List.of(
                apiDocumentationCitationCandidate(
                        "object", "A utility of() method", javaApiPageUrl("java.lang", "Object.html")),
                apiDocumentationCitationCandidate(
                        "string", "A utility of() method", javaApiPageUrl("java.lang", "String.html")),
                apiDocumentationCitationCandidate(
                        "integer", "A utility of() method", javaApiPageUrl("java.lang", "Integer.html")),
                apiDocumentationCitationCandidate("list", "static <E> List<E> of(E element)", listPageUrl));
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(citationQuery), eq(4), same(officialDocumentationConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(qdrantCandidates, List.of()));

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations(citationQuery, officialDocumentationConstraint);

        assertEquals(3, citationOutcome.citations().size());
        assertEquals(listPageUrl, citationOutcome.citations().getFirst().getUrl());
        assertTrue(citationOutcome.citations().stream().anyMatch(citation -> listPageUrl.equals(citation.getUrl())));
        assertEquals(0, citationOutcome.failedConversionCount());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void versionedCitationDiscoveryCombinesOfficialScopeAndQueryVersionBeforeDispatch() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedCombinedConstraint =
                officialDocumentationConstraint.withDocVersion(REPRESENTED_JAVA_API_SOURCE.javaRelease());
        String citationQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " List.of";
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(citationQuery), anyInt(), eq(expectedCombinedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(), List.of()));

        retrievalService.discoverCitations(citationQuery, officialDocumentationConstraint);

        verify(hybridSearchService)
                .searchDocumentationCitationsOutcome(eq(citationQuery), anyInt(), eq(expectedCombinedConstraint));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void zeroCitationLimitReturnsWithoutDispatchingAQuery() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchCitations(0);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations("Java strings", guidedConstraint);

        assertTrue(citationOutcome.citations().isEmpty());
        assertEquals(0, citationOutcome.failedConversionCount());
        verify(hybridSearchService, never())
                .searchDocumentationCitationsOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void citationDiscoveryPropagatesHybridFailuresWithoutRerankerFallback() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        HybridSearchPartialFailureException.CollectionSearchFailure collectionFailure =
                new HybridSearchPartialFailureException.CollectionSearchFailure("java-docs", "Timeout", "5s");
        when(hybridSearchService.searchDocumentationCitationsOutcome(anyString(), anyInt(), same(guidedConstraint)))
                .thenThrow(new HybridSearchPartialFailureException("collection failure", List.of(collectionFailure)));

        assertThrows(
                HybridSearchPartialFailureException.class,
                () -> retrievalService.discoverCitations("Java strings", guidedConstraint));

        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    private static Document citationCandidateDocument(
            String documentId, String sourceText, String contentHash, String sourceUrl) {
        return Document.builder()
                .id(documentId)
                .text(sourceText)
                .metadata("hash", contentHash)
                .metadata("url", sourceUrl)
                .build();
    }

    private static Document apiDocumentationCitationCandidate(
            String documentId, String documentText, String sourceUrl) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, sourceUrl)
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .build();
    }

    private static String javaApiPageUrl(String packageName, String pageFilename) {
        return REPRESENTED_JAVA_API_SOURCE.remoteBaseUrl()
                + "java.base/"
                + packageName.replace('.', '/')
                + "/"
                + pageFilename;
    }

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
    void preservesDistinctSamePageChunksForRerankingAndRetainsDistinctAnchoredCitations() {
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
        assertEquals(3, citationOutcome.citations().size());
        assertEquals(stringJavadocUrl, citationOutcome.citations().get(1).getUrl());
        assertEquals("First Javadoc chunk", citationOutcome.citations().get(1).getSnippet());
        assertEquals(stringJavadocUrl, citationOutcome.citations().get(2).getUrl());
        assertEquals("assert(...)", citationOutcome.citations().get(2).getAnchor());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void keepsDistinctUnmappedLocalDocumentsAndRedactsTheirCitations() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        DocumentFactory documentFactory = mock(DocumentFactory.class);
        AppProperties appProperties = new AppProperties();
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, documentFactory);
        String firstUnmappedLocalUrl = "file:///unmapped/first.html";
        String secondUnmappedLocalUrl = "file:///unmapped/second.html";

        Document firstUnmappedLocalDocument = Document.builder()
                .id("first-unmapped-local")
                .text("First local document")
                .metadata("url", firstUnmappedLocalUrl)
                .build();
        Document secondUnmappedLocalDocument = Document.builder()
                .id("second-unmapped-local")
                .text("Second local document")
                .metadata("url", secondUnmappedLocalUrl)
                .build();
        List<Document> retrievalCandidates = List.of(firstUnmappedLocalDocument, secondUnmappedLocalDocument);
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class)))
                .thenReturn(new HybridSearchService.SearchOutcome(retrievalCandidates, List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenAnswer(rerankerInvocation -> {
            List<Document> deduplicatedCandidates = rerankerInvocation.getArgument(1);
            return deduplicatedCandidates;
        });

        RetrievalService.RetrievalOutcome retrievalOutcome = retrievalService.retrieveOutcome("Local documentation");
        RetrievalService.CitationOutcome citationOutcome = retrievalService.toCitations(retrievalOutcome.documents());
        String redactedLocalCitationUrl = DocsSourceRegistry.normalizeDocUrl(firstUnmappedLocalUrl);

        assertEquals(retrievalCandidates, retrievalOutcome.documents());
        assertEquals(2, citationOutcome.citations().size());
        assertEquals(
                List.of(redactedLocalCitationUrl, redactedLocalCitationUrl),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertTrue(citationOutcome.citations().stream()
                .map(citation -> citation.getUrl())
                .noneMatch(citationUrl -> citationUrl.contains("/unmapped/")));
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
}
