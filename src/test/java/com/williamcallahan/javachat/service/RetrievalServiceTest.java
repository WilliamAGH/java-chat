package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Verifies retrieval outcome behavior around hybrid-search notices and strict failures.
 */
class RetrievalServiceTest {

    private static final Logger RETRIEVAL_SERVICE_LOGGER = (Logger) LoggerFactory.getLogger(RetrievalService.class);

    @Test
    void constrainedRetrievalPassesTheCallerConstraintInstanceToHybridSearch() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java", "java/java25-complete"));
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(guidedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

        retrievalService.retrieveOutcome("Java strings", guidedConstraint);

        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), same(guidedConstraint));
    }

    @Test
    void citationDiscoveryUsesConfiguredCitationLimitWithoutInvokingTheLlmReranker() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(3);
        appProperties.getRag().setSearchCitations(1);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java", "java/java25-complete"));
        Document firstHybridDocument = Document.builder()
                .id("first-hybrid-document")
                .text("String API documentation")
                .metadata("hash", "first-hash")
                .build();
        Document secondHybridDocument = Document.builder()
                .id("second-hybrid-document")
                .text("String tutorial")
                .metadata("hash", "second-hash")
                .build();
        Document thirdHybridDocument = Document.builder()
                .id("third-hybrid-document")
                .text("String examples")
                .metadata("hash", "third-hash")
                .build();
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(guidedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(
                        List.of(firstHybridDocument, secondHybridDocument, thirdHybridDocument), List.of()));

        List<Document> citationDocuments =
                retrievalService.retrieveForCitationDiscovery("Java strings", guidedConstraint);

        assertEquals(List.of(firstHybridDocument), citationDocuments);
        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), same(guidedConstraint));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void citationDiscoveryPropagatesHybridFailuresWithoutRerankerFallback() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java", "java/java25-complete"));
        HybridSearchPartialFailureException.CollectionSearchFailure collectionFailure =
                new HybridSearchPartialFailureException.CollectionSearchFailure("java-docs", "Timeout", "5s");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(guidedConstraint)))
                .thenThrow(new HybridSearchPartialFailureException("collection failure", List.of(collectionFailure)));

        assertThrows(
                HybridSearchPartialFailureException.class,
                () -> retrievalService.retrieveForCitationDiscovery("Java strings", guidedConstraint));

        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
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
        assertEquals(
                stringJavadocUrl + "#assert(...)",
                citationOutcome.citations().get(2).getUrl());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsCollapsesDocumentsResolvedToTheSameJavadocMemberAnchor() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("first-substring-chunk", "substring(int,int)"),
                        javadocCitationDocument("duplicate-substring-chunk", "substring(int,int)")));

        assertEquals(
                List.of(stringJavadocUrl + "#substring(int,int)"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsRetainsDocumentsResolvedToDistinctJavadocMemberAnchors() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("substring-chunk", "substring(int,int)"),
                        javadocCitationDocument("char-at-chunk", "charAt(int)")));

        assertEquals(
                List.of(stringJavadocUrl + "#substring(int,int)", stringJavadocUrl + "#charAt(int)"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsDoesNotGenerateRecordAnchorFromUppercaseInvocationArguments() {
        String recordJavadocUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/lang/Record.html";
        Document recordDocument = Document.builder()
                .id("record-equals-invocation")
                .text("return equals(this.SomeField, r.OTHER_FIELD);")
                .metadata("url", recordJavadocUrl)
                .metadata("package", "java.lang")
                .metadata("docType", "api-docs")
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(recordDocument));

        assertEquals(
                List.of(recordJavadocUrl),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsDoesNotRefineMemberAnchorsOutsideApiDocs() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        Document tutorialDocument = Document.builder()
                .id("tutorial-substring-chunk")
                .text("substring(int,int)")
                .metadata("url", stringJavadocUrl)
                .metadata("package", "java.lang")
                .metadata("docType", "tutorial")
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(tutorialDocument));

        assertEquals(
                List.of(stringJavadocUrl),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsDoesNotRefineNestedTypeUrlsOutsideApiDocs() {
        String mapJavadocUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/util/Map.html";
        Document tutorialDocument = Document.builder()
                .id("tutorial-map-entry-chunk")
                .text("Map.Entry")
                .metadata("url", mapJavadocUrl)
                .metadata("package", "java.util")
                .metadata("docType", "tutorial")
                .build();

        RetrievalService.CitationOutcome citationOutcome = citationService().toCitations(List.of(tutorialDocument));

        assertEquals(
                List.of(mapJavadocUrl),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsCollapsesFragmentlessDocumentsFromTheSamePage() {
        String stringJavadocUrl = javaLangStringJavadocUrl();
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        javadocCitationDocument("first-class-overview-chunk", "Class overview"),
                        javadocCitationDocument("duplicate-class-overview-chunk", "Class overview")));

        assertEquals(
                List.of(stringJavadocUrl),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
    }

    @Test
    void toCitationsRetainsDistinctPdfPageAnchors() {
        String pdfCitationUrl = "https://example.test/books/Reference.pdf";
        RetrievalService.CitationOutcome citationOutcome = citationService()
                .toCitations(List.of(
                        Document.builder()
                                .id("first-pdf-page-chunk")
                                .text("First PDF page")
                                .metadata("url", pdfCitationUrl + "#page=1")
                                .build(),
                        Document.builder()
                                .id("second-pdf-page-chunk")
                                .text("Second PDF page")
                                .metadata("url", pdfCitationUrl + "#page=2")
                                .build()));

        assertEquals(
                List.of(pdfCitationUrl + "#page=1", pdfCitationUrl + "#page=2"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
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

        RetrievalService.CitationOutcome citationOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRIEVAL_SERVICE_LOGGER)) {
            citationOutcome = retrievalService.toCitations(List.of(malformedDocument, validDocument));

            assertEquals(2, expectedLogEvents.events().size());
            var conversionFailureWarning = expectedLogEvents.events().getFirst();
            assertEquals(Level.WARN, conversionFailureWarning.getLevel());
            assertEquals(
                    "Citation conversion failed (exceptionType=IllegalStateException,"
                            + " docUrl=[unprintable:BrokenUrlValue], docTitle=Broken citation)",
                    conversionFailureWarning.getFormattedMessage());
            assertNotNull(conversionFailureWarning.getThrowableProxy());
            assertEquals(
                    IllegalStateException.class.getName(),
                    conversionFailureWarning.getThrowableProxy().getClassName());

            var conversionSummaryWarning = expectedLogEvents.events().get(1);
            assertEquals(Level.WARN, conversionSummaryWarning.getLevel());
            assertEquals(
                    "Citation conversion completed with 1 failure(s) out of 2 documents",
                    conversionSummaryWarning.getFormattedMessage());
            assertNull(conversionSummaryWarning.getThrowableProxy());
        }

        assertEquals(1, citationOutcome.citations().size());
        assertEquals(1, citationOutcome.failedConversionCount());
        assertEquals("String", citationOutcome.citations().get(0).getTitle());
        assertTrue(citationOutcome.citations().get(0).getUrl().contains("docs.oracle.com"));
    }

    private static RetrievalService citationService() {
        return new RetrievalService(
                mock(HybridSearchService.class),
                new AppProperties(),
                mock(RerankerService.class),
                mock(DocumentFactory.class));
    }

    private static Document javadocCitationDocument(String documentId, String sourceText) {
        return Document.builder()
                .id(documentId)
                .text(sourceText)
                .metadata("url", javaLangStringJavadocUrl())
                .metadata("package", "java.lang")
                .metadata("docType", "api-docs")
                .build();
    }

    private static String javaLangStringJavadocUrl() {
        return DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                + "java.base/java/lang/String.html";
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
