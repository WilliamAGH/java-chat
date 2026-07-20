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
    void nonJavaSourceScopeKeepsVersionMentionAsSemanticQueryTextWithoutVersionFilter() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint kotlinConstraint = RetrievalConstraint.forOfficialDocSets(List.of("kotlin"));
        Document kotlinDocument = versionedDocument("kotlin-interop", "", "kotlin-hash");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(kotlinConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(kotlinDocument), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(kotlinDocument));

        RetrievalService.RetrievalOutcome retrievalOutcome =
                retrievalService.retrieveOutcome("How does Kotlin interoperate with Java 21?", kotlinConstraint);

        assertEquals(List.of(kotlinDocument), retrievalOutcome.documents());
        verify(hybridSearchService)
                .searchOutcome(eq("How does Kotlin interoperate with Java 21?"), anyInt(), same(kotlinConstraint));
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
                officialDocumentationConstraint.withDocVersions(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease()));
        String versionedQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " List.of";
        Document versionedDocument =
                versionedDocument("represented-version", REPRESENTED_JAVA_API_SOURCE.javaRelease(), "represented-hash");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(expectedCombinedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(versionedDocument), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(versionedDocument));

        retrievalService.retrieveOutcome(versionedQuery, officialDocumentationConstraint);

        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), eq(expectedCombinedConstraint));
        assertEquals(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease()), expectedCombinedConstraint.docVersions());
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
                apiDocumentationCitationCandidate("object", "A utility of() method", "java.lang", "Object.html"),
                apiDocumentationCitationCandidate("string", "A utility of() method", "java.lang", "String.html"),
                apiDocumentationCitationCandidate("integer", "A utility of() method", "java.lang", "Integer.html"),
                apiDocumentationCitationCandidate(
                        "list", "static <E> List<E> of(E element)", "java.util", "List.html"));
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
                officialDocumentationConstraint.withDocVersions(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease()));
        String citationQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " List.of";
        Document versionedCitation = versionedCitationDocument(
                "represented-version-citation", REPRESENTED_JAVA_API_SOURCE.javaRelease(), "represented-hash");
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(citationQuery), anyInt(), eq(expectedCombinedConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(versionedCitation), List.of()));

        retrievalService.discoverCitations(citationQuery, officialDocumentationConstraint);

        verify(hybridSearchService)
                .searchDocumentationCitationsOutcome(eq(citationQuery), anyInt(), eq(expectedCombinedConstraint));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void multiVersionRetrievalSearchesEveryReleaseAndRetainsCoverageAfterReranking() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(2);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = officialDocumentationConstraint.withDocVersions(List.of("21"));
        RetrievalConstraint java24Constraint = officialDocumentationConstraint.withDocVersions(List.of("24"));
        Document java21Document = versionedDocument("java-21", "21", "shared-content-hash");
        Document secondJava21Document = versionedDocument("java-21-secondary", "21", "secondary-hash");
        Document java24Document = versionedDocument("java-24", "24", "shared-content-hash");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(java21Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(
                        List.of(java21Document, secondJava21Document), List.of()));
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(java24Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(java24Document), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), eq(2)))
                .thenReturn(List.of(java21Document, secondJava21Document));

        RetrievalService.RetrievalOutcome retrievalOutcome = retrievalService.retrieveOutcome(
                "Compare Java 21 and Java 24 List.of", officialDocumentationConstraint);

        assertEquals(
                List.of("21", "24"),
                retrievalOutcome.documents().stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), eq(java21Constraint));
        verify(hybridSearchService).searchOutcome(anyString(), anyInt(), eq(java24Constraint));
    }

    @Test
    void exactMultiVersionOverloadUsesAuthoritativeDocumentsFromEveryRequestedRelease() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(3);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = officialDocumentationConstraint.withDocVersions(List.of("21"));
        RetrievalConstraint java24Constraint = officialDocumentationConstraint.withDocVersions(List.of("24"));
        String exactComparisonQuery =
                "Compare Java 21 and Java 24 for java.util.List.of(E, E). Use evidence from both releases.";
        Document java21ExactOverload = exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21");
        Document java24ExactOverload = exactListOfOverloadDocument("java-24-exact", "24", "exact-hash-24");
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(exactComparisonQuery), anyInt(), eq(java21Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(java21ExactOverload), List.of()));
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(exactComparisonQuery), anyInt(), eq(java24Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(java24ExactOverload), List.of()));

        RetrievalService.RetrievalOutcome retrievalOutcome = retrievalService.retrieveWithLimitOutcome(
                exactComparisonQuery, 3, 1_000, officialDocumentationConstraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.toCitationsForQuery(exactComparisonQuery, retrievalOutcome.documents());

        assertEquals(List.of(java21ExactOverload, java24ExactOverload), retrievalOutcome.documents());
        assertEquals(
                List.of("21", "24"),
                retrievalOutcome.documents().stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        assertEquals(
                List.of("of(E,E)", "of(E,E)"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getAnchor())
                        .toList());
        assertEquals(
                List.of(
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html",
                        "https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/List.html"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        verify(hybridSearchService, never()).searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void exactJavaSyntaxInNonJavaScopeDoesNotDispatchJavaApiCitationRetrieval() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint kotlinConstraint = RetrievalConstraint.forOfficialDocSets(List.of("kotlin"));
        String kotlinQuery = "How can Kotlin call java.util.List.of(E, E)?";
        Document kotlinDocument = versionedDocument("kotlin-list-call", "", "kotlin-list-hash");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(kotlinConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(kotlinDocument), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(kotlinDocument));

        RetrievalService.RetrievalOutcome retrievalOutcome =
                retrievalService.retrieveOutcome(kotlinQuery, kotlinConstraint);

        assertEquals(List.of(kotlinDocument), retrievalOutcome.documents());
        verify(hybridSearchService, never())
                .searchDocumentationCitationsOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
    }

    @Test
    void unscopedExactLookingSyntaxStaysOnPrimaryHybridRetrieval() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        String projectQuery = "Explain Widget.of(E, E)";
        RetrievalConstraint unconstrained = RetrievalConstraint.none();
        Document projectDocument = versionedDocument("project-widget", "", "project-widget-hash");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(unconstrained)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(projectDocument), List.of()));
        when(rerankerService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(projectDocument));

        RetrievalService.RetrievalOutcome retrievalOutcome =
                retrievalService.retrieveOutcome(projectQuery, unconstrained);

        assertEquals(List.of(projectDocument), retrievalOutcome.documents());
        verify(hybridSearchService, never())
                .searchDocumentationCitationsOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
    }

    @Test
    void unversionedExactOverloadRespectsConfiguredReturnLimit() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(2);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        String exactQuery = "Explain java.util.List.of(E, E)";
        List<Document> exactOverloadDocuments = List.of(
                exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21"),
                exactListOfOverloadDocument("java-24-exact", "24", "exact-hash-24"),
                exactListOfOverloadDocument("java-25-exact", "25", "exact-hash-25"));
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(exactQuery), anyInt(), same(officialDocumentationConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(exactOverloadDocuments, List.of()));

        RetrievalService.RetrievalOutcome retrievalOutcome =
                retrievalService.retrieveOutcome(exactQuery, officialDocumentationConstraint);

        assertEquals(exactOverloadDocuments.subList(0, 2), retrievalOutcome.documents());
        verify(hybridSearchService, never()).searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void exactMultiVersionOverloadFailsWhenOneRequestedReleaseHasNoEvidence() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = officialDocumentationConstraint.withDocVersions(List.of("21"));
        RetrievalConstraint java24Constraint = officialDocumentationConstraint.withDocVersions(List.of("24"));
        String exactComparisonQuery = "Compare Java 21 and Java 24 for java.util.List.of(E, E).";
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(exactComparisonQuery), anyInt(), eq(java21Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(
                        List.of(exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21")), List.of()));
        when(hybridSearchService.searchDocumentationCitationsOutcome(
                        eq(exactComparisonQuery), anyInt(), eq(java24Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(), List.of()));

        assertThrows(
                IllegalStateException.class,
                () -> retrievalService.retrieveOutcome(exactComparisonQuery, officialDocumentationConstraint));
        verify(hybridSearchService, never()).searchOutcome(anyString(), anyInt(), any(RetrievalConstraint.class));
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void constrainedDocumentLimitRetainsEveryRequestedRelease() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(6);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = officialDocumentationConstraint.withDocVersions(List.of("21"));
        RetrievalConstraint java24Constraint = officialDocumentationConstraint.withDocVersions(List.of("24"));
        List<Document> java21Documents = List.of(
                versionedDocument("java-21-a", "21", "hash-21-a"),
                versionedDocument("java-21-b", "21", "hash-21-b"),
                versionedDocument("java-21-c", "21", "hash-21-c"),
                versionedDocument("java-21-d", "21", "hash-21-d"),
                versionedDocument("java-21-e", "21", "hash-21-e"));
        Document java24Document = versionedDocument("java-24", "24", "hash-24");
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(java21Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(java21Documents, List.of()));
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), eq(java24Constraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(List.of(java24Document), List.of()));
        List<Document> rerankedDocuments = new java.util.ArrayList<>(java21Documents);
        rerankedDocuments.add(java24Document);
        when(rerankerService.rerank(anyString(), anyList(), eq(6))).thenReturn(rerankedDocuments);

        RetrievalService.RetrievalOutcome limitedOutcome = retrievalService.retrieveWithLimitOutcome(
                "Compare Java 21 and Java 24 List.of", 3, 1_000, officialDocumentationConstraint);

        assertEquals(3, limitedOutcome.documents().size());
        assertEquals(
                List.of("21", "21", "24"),
                limitedOutcome.documents().stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
    }

    @Test
    void constrainedDocumentLimitStillAppliesWithoutARequestedRelease() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setSearchReturnK(6);
        RetrievalService retrievalService =
                new RetrievalService(hybridSearchService, appProperties, rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        List<Document> retrievedDocuments = List.of(
                versionedDocument("document-a", "", "hash-a"),
                versionedDocument("document-b", "", "hash-b"),
                versionedDocument("document-c", "", "hash-c"),
                versionedDocument("document-d", "", "hash-d"),
                versionedDocument("document-e", "", "hash-e"),
                versionedDocument("document-f", "", "hash-f"));
        when(hybridSearchService.searchOutcome(anyString(), anyInt(), same(officialDocumentationConstraint)))
                .thenReturn(new HybridSearchService.SearchOutcome(retrievedDocuments, List.of()));
        when(rerankerService.rerank(anyString(), anyList(), eq(6))).thenReturn(retrievedDocuments);

        RetrievalService.RetrievalOutcome limitedOutcome = retrievalService.retrieveWithLimitOutcome(
                "Explain Java strings", 3, 1_000, officialDocumentationConstraint);

        assertEquals(3, limitedOutcome.documents().size());
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
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, sourceUrl)
                .build();
    }

    private static Document versionedDocument(String documentId, String documentVersion, String contentHash) {
        return Document.builder()
                .id(documentId)
                .text("Java " + documentVersion + " documentation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, "https://docs.example.test/" + documentVersion + "/")
                .build();
    }

    private static Document versionedCitationDocument(String documentId, String documentVersion, String contentHash) {
        return Document.builder()
                .id(documentId)
                .text("Java " + documentVersion + " citation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example.test/" + documentVersion + "/List.html")
                .build();
    }

    private static Document exactListOfOverloadDocument(String documentId, String documentVersion, String contentHash) {
        DocsSourceRegistry.JavaApiDocumentationSource documentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().stream()
                        .filter(candidateSource -> documentVersion.equals(candidateSource.javaRelease()))
                        .findFirst()
                        .orElseThrow();
        return Document.builder()
                .id(documentId)
                .text("static <E> List<E> of(E e1, E e2) Returns an unmodifiable list containing two elements")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        documentationSource.remoteBaseUrl() + "java.base/java/util/List.html")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.util")
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, "List.html")
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, "of(E,E)")
                .build();
    }

    private static Document apiDocumentationCitationCandidate(
            String documentId, String documentText, String packageName, String pageFilename) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiPageUrl(packageName, pageFilename))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, packageName)
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
        candidateDocument
                .getMetadata()
                .put(QdrantPayloadFieldSchema.URL_FIELD, "https://docs.example.com/java/streams");
        candidateDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "hash-1");

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
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, "https://example.org/java/reference")
                .build();
        Document canonicalUrlDuplicateWithoutHash = Document.builder()
                .id("url-only-duplicate")
                .text("URL-only duplicate candidate")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, "https://example.org//java/reference")
                .build();
        Document firstJavadocChunk = Document.builder()
                .id("first-javadoc-chunk")
                .text("First Javadoc chunk")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, stringJavadocUrl)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, "first-content-hash")
                .build();
        Document secondJavadocChunkWithDistinctHash = Document.builder()
                .id("second-javadoc-chunk")
                .text("Second Javadoc chunk")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, stringJavadocUrl + "#assert(...)")
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, "second-content-hash")
                .build();
        Document sameContentHashWithDifferentUrl = Document.builder()
                .id("same-content-hash")
                .text("Duplicate content under another URL")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiBaseUrl + "java.base/java/lang/Object.html")
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, "first-content-hash")
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
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, firstUnmappedLocalUrl)
                .build();
        Document secondUnmappedLocalDocument = Document.builder()
                .id("second-unmapped-local")
                .text("Second local document")
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, secondUnmappedLocalUrl)
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
