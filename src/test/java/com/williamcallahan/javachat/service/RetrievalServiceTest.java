package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final Set<String> JAVA_API_DOCUMENTATION_SOURCE_IDENTITIES =
            Set.copyOf(DocsSourceRegistry.javaApiDocumentationSources().stream()
                    .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                    .toList());
    private static final String DEFAULT_JAVA_API_DOCUMENTATION_SOURCE_IDENTITY =
            DocsSourceRegistry.javaApiDocumentationSources().stream()
                    .filter(source -> Integer.toString(
                                    new AppProperties().getDocs().getJdkVersion())
                            .equals(source.javaRelease()))
                    .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                    .findFirst()
                    .orElseThrow();
    private static final int DOCUMENTATION_CITATION_CANDIDATE_LIMIT = 10;
    private static final String JAVA_21_RELEASE = "21";
    private static final String JAVA_26_RELEASE = "26";
    private static final String EXACT_LIST_OF_QUERY = "Compare Java 21 and Java 26 for java.util.List.of(E, E).";
    private static final String JAVA_21_EXACT_LIST_DOCUMENT_ID = "java-21-exact";
    private static final String JAVA_26_EXACT_LIST_DOCUMENT_ID = "java-26-exact";
    private static final String JAVA_21_EXACT_LIST_CONTENT_HASH = "exact-hash-21";
    private static final String JAVA_26_EXACT_LIST_CONTENT_HASH = "exact-hash-26";
    private static final String EXACT_LIST_OVERLOAD_TEXT =
            "static <E> List<E> of(E e1, E e2) Returns an unmodifiable list containing two elements";
    private static final String EXACT_LIST_OVERLOAD_ANCHOR = "of(E,E)";
    private static final String JAVA_UTIL_PACKAGE = "java.util";
    private static final String JAVA_LIST_API_PAGE = "List.html";
    private static final String JAVA_21_LIST_API_URL =
            "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html";
    private static final String JAVA_26_LIST_API_URL =
            "https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/List.html";
    private static final Duration STAGE_DEADLINE_ASSERTION_TOLERANCE = Duration.ofSeconds(1);

    @Test
    void genericBroadOfficialScopeUsesDefaultJavaApiSourceAndRetainsEveryNonJavaSource() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        when(hybridSearchService.search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of());
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of());

        retrievalService.retrieve("Java strings", guidedConstraint);

        verify(hybridSearchService).search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong());
        assertEquals(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES, guidedConstraint.docSet());
        assertEquals(
                List.of(DEFAULT_JAVA_API_DOCUMENTATION_SOURCE_IDENTITY),
                expectedScopedConstraint.docSet().stream()
                        .filter(JAVA_API_DOCUMENTATION_SOURCE_IDENTITIES::contains)
                        .toList());
        assertEquals(
                OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES.stream()
                        .filter(sourceIdentity -> !JAVA_API_DOCUMENTATION_SOURCE_IDENTITIES.contains(sourceIdentity))
                        .toList(),
                expectedScopedConstraint.docSet().stream()
                        .filter(sourceIdentity -> !JAVA_API_DOCUMENTATION_SOURCE_IDENTITIES.contains(sourceIdentity))
                        .toList());
    }

    @Test
    void bareJavaMemberUsesDeterministicSparseEvidenceWithoutReranking() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        String memberQuery = "Explain Javadoc String.formatted";
        Document staticFormatDocument = apiDocumentationMemberCitationCandidate(
                "static-format",
                "Formats with a format string.",
                "java.lang",
                "String.html",
                "format(java.lang.String,java.lang.Object...)");
        Document formattedDocument = apiDocumentationMemberCitationCandidate(
                "formatted",
                "Formats this string with arguments.",
                "java.lang",
                "String.html",
                "formatted(java.lang.Object...)");
        when(hybridSearchService.searchDocumentationCitations(
                        eq(memberQuery), eq(10), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of(staticFormatDocument, formattedDocument));

        List<Document> retrievalOutcome = retrievalService.retrieve(memberQuery, officialDocumentationConstraint);

        assertEquals(List.of(formattedDocument), retrievalOutcome);
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
    }

    @Test
    void versionedBareJavaMemberUsesTheRequestedReleaseEvidence() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java25Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "25");
        String memberQuery = "Explain Java 25 String::formatted";
        Document formattedDocument = Document.builder()
                .id("java-25-formatted")
                .text("Formats this string with arguments.")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, "25")
                .metadata(
                        QdrantPayloadFieldSchema.DOC_SET_FIELD,
                        javaApiSource("25").relativeMirrorPath())
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, "java-25-formatted-hash")
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        javaApiSource("25").remoteBaseUrl() + "java.base/java/lang/String.html")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.lang")
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, "String.html")
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, "formatted(java.lang.Object...)")
                .build();
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(memberQuery), eq(10), eq(List.of(java25Constraint)), anyLong()))
                .thenReturn(List.of(List.of(formattedDocument)));

        List<Document> retrievalOutcome = retrievalService.retrieve(memberQuery, officialDocumentationConstraint);

        verify(hybridSearchService)
                .searchDocumentationCitationsByConstraint(
                        eq(memberQuery), eq(10), eq(List.of(java25Constraint)), anyLong());
        assertEquals(List.of(formattedDocument), retrievalOutcome);
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
    }

    @Test
    void otherOfficialApiMembersRemainOnGenericHybridRetrieval() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        Document springDocument = versionedDocument("spring-application-run", "", "spring-run-hash");
        when(hybridSearchService.search(
                        eq("Explain SpringApplication.run"), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of(springDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(springDocument));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("Explain SpringApplication.run", officialDocumentationConstraint);

        assertEquals(List.of(springDocument), retrievalOutcome);
        verify(hybridSearchService, never())
                .searchDocumentationCitations(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
    }

    @Test
    void chainedMemberQueriesRemainOnGenericHybridRetrieval() {
        assertGenericOfficialMemberQuery("Explain Javadoc Stream.of().map(Function)");
    }

    @Test
    void malformedMemberSignaturesRemainOnGenericHybridRetrieval() {
        assertGenericOfficialMemberQuery("Explain Javadoc List.of(E,");
    }

    @Test
    void retrievalReportsSearchThenRerankProgressInOrder() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        when(hybridSearchService.search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of());
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of());

        List<String> progressSummaries = new ArrayList<>();
        retrievalService.retrieve(
                "Java records", guidedConstraint, progressNotice -> progressSummaries.add(progressNotice.summary()));

        assertEquals(List.of("Searching the Java documentation index", "Reviewing the top matches"), progressSummaries);
    }

    @Test
    void nonJavaSourceScopeKeepsVersionMentionAsSemanticQueryTextWithoutVersionFilter() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint kotlinConstraint = RetrievalConstraint.forOfficialDocSets(List.of("kotlin"));
        Document kotlinDocument = versionedDocument("kotlin-interop", "", "kotlin-hash");
        when(hybridSearchService.search(anyString(), anyInt(), same(kotlinConstraint), anyLong()))
                .thenReturn(List.of(kotlinDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(kotlinDocument));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("How does Kotlin interoperate with Java 21?", kotlinConstraint);

        assertEquals(List.of(kotlinDocument), retrievalOutcome);
        verify(hybridSearchService)
                .search(
                        eq(
                                "JDK 21 Java SE 21 Java 21 release documentation: How does Kotlin interoperate with Java 21?"),
                        anyInt(),
                        same(kotlinConstraint),
                        anyLong());
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
                javaEvidenceConstraint(officialDocumentationConstraint, REPRESENTED_JAVA_API_SOURCE.javaRelease());
        String versionedQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " collections";
        Document versionedDocument =
                versionedDocument("represented-version", REPRESENTED_JAVA_API_SOURCE.javaRelease(), "represented-hash");
        when(hybridSearchService.searchByConstraint(
                        anyString(), anyInt(), eq(List.of(expectedCombinedConstraint)), anyLong()))
                .thenReturn(List.of(List.of(versionedDocument)));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(versionedDocument));

        retrievalService.retrieve(versionedQuery, officialDocumentationConstraint);

        verify(hybridSearchService)
                .searchByConstraint(anyString(), anyInt(), eq(List.of(expectedCombinedConstraint)), anyLong());
        assertEquals(List.of(REPRESENTED_JAVA_API_SOURCE.javaRelease()), expectedCombinedConstraint.docVersions());
        assertEquals("official", expectedCombinedConstraint.sourceKind());
        assertEquals(List.of(REPRESENTED_JAVA_API_SOURCE.relativeMirrorPath()), expectedCombinedConstraint.docSet());
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
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
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
        when(hybridSearchService.searchDocumentationCitations(
                        anyString(), eq(4), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(citationCandidates);

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
        verify(hybridSearchService)
                .searchDocumentationCitations(anyString(), eq(4), eq(expectedScopedConstraint), anyLong());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        String citationQuery = "What does Javadoc List.of return?";
        String listPageUrl = javaApiPageUrl("java.util", "List.html");
        List<Document> qdrantCandidates = List.of(
                apiDocumentationCitationCandidate("object", "A utility of() method", "java.lang", "Object.html"),
                apiDocumentationCitationCandidate("string", "A utility of() method", "java.lang", "String.html"),
                apiDocumentationCitationCandidate("integer", "A utility of() method", "java.lang", "Integer.html"),
                apiDocumentationMemberCitationCandidate(
                        "list", "static <E> List<E> of(E element)", "java.util", "List.html", "of(E)"));
        when(hybridSearchService.searchDocumentationCitations(
                        eq(citationQuery), eq(4), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(qdrantCandidates);

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations(citationQuery, officialDocumentationConstraint);

        assertEquals(1, citationOutcome.citations().size());
        assertEquals(listPageUrl, citationOutcome.citations().getFirst().getUrl());
        assertEquals("of(E)", citationOutcome.citations().getFirst().getAnchor());
        assertEquals(0, citationOutcome.failedConversionCount());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
                javaEvidenceConstraint(officialDocumentationConstraint, REPRESENTED_JAVA_API_SOURCE.javaRelease());
        String citationQuery = "Java " + REPRESENTED_JAVA_API_SOURCE.javaRelease() + " collections";
        Document versionedCitation = versionedCitationDocument(
                "represented-version-citation", REPRESENTED_JAVA_API_SOURCE.javaRelease(), "represented-hash");
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(citationQuery), anyInt(), eq(List.of(expectedCombinedConstraint)), anyLong()))
                .thenReturn(List.of(List.of(versionedCitation)));

        retrievalService.discoverCitations(citationQuery, officialDocumentationConstraint);

        verify(hybridSearchService)
                .searchDocumentationCitationsByConstraint(
                        eq(citationQuery), anyInt(), eq(List.of(expectedCombinedConstraint)), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java26Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "26");
        Document java21Document = versionedDocument("java-21", "21", "shared-content-hash");
        Document secondJava21Document = versionedDocument("java-21-secondary", "21", "secondary-hash");
        Document java26Document = versionedDocument("java-26", "26", "shared-content-hash");
        when(hybridSearchService.searchByConstraint(
                        anyString(), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong()))
                .thenReturn(List.of(List.of(java21Document, secondJava21Document), List.of(java26Document)));
        when(rerankerService.rerank(anyString(), anyList(), eq(2), anyLong()))
                .thenReturn(List.of(java21Document, secondJava21Document));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("Compare Java 21 and Java 26 collections", officialDocumentationConstraint);

        assertEquals(
                List.of("21", "26"),
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        verify(hybridSearchService)
                .searchByConstraint(anyString(), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong());
        assertEquals(List.of("java/java21-complete"), java21Constraint.docSet());
        assertEquals(List.of("java/java26-complete"), java26Constraint.docSet());
    }

    @Test
    void missingJavaReleaseSearchesNearestOlderAndNewerDocumentation() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java25Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "25");
        Document java21Document = versionedDocument("java-21", "21", "hash-21");
        Document java25Document = versionedDocument("java-25", "25", "hash-25");
        when(hybridSearchService.searchByConstraint(
                        anyString(), eq(10), eq(List.of(java21Constraint, java25Constraint)), anyLong()))
                .thenReturn(List.of(List.of(java21Document), List.of(java25Document)));
        when(rerankerService.rerank(anyString(), anyList(), eq(2), anyLong()))
                .thenReturn(List.of(java21Document, java25Document));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("How does this work in Java 22?", officialDocumentationConstraint);

        assertEquals(
                List.of("21", "25"),
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        verify(hybridSearchService)
                .searchByConstraint(anyString(), eq(10), eq(List.of(java21Constraint, java25Constraint)), anyLong());
    }

    @Test
    void missingDependencyVersionSearchesNearestSameFamilyVersions() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint hikaricp702Constraint =
                documentationEvidenceConstraint("hikaricp/7.0.2/api", "api-docs", "7.0.2");
        RetrievalConstraint hikaricp710Constraint =
                documentationEvidenceConstraint("hikaricp/7.1.0/api", "api-docs", "7.1.0");
        Document hikaricp702Document = versionedDocument("hikaricp-702", "7.0.2", "hash-702", "hikaricp/7.0.2/api");
        Document hikaricp710Document = versionedDocument("hikaricp-710", "7.1.0", "hash-710", "hikaricp/7.1.0/api");
        when(hybridSearchService.searchByConstraint(
                        anyString(), eq(10), eq(List.of(hikaricp702Constraint, hikaricp710Constraint)), anyLong()))
                .thenReturn(List.of(List.of(hikaricp702Document), List.of(hikaricp710Document)));
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        anyString(), eq(10), eq(List.of(hikaricp702Constraint, hikaricp710Constraint)), anyLong()))
                .thenReturn(List.of(List.of(hikaricp702Document), List.of(hikaricp710Document)));
        when(rerankerService.rerank(anyString(), anyList(), eq(2), anyLong()))
                .thenReturn(List.of(hikaricp702Document, hikaricp710Document));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("HikariCP 7.0.5 connection pooling", officialDocumentationConstraint);
        List<Document> limitedOutcome = retrievalService.retrieveWithLimit(
                "HikariCP 7.0.5 connection pooling", 2, 1_000, officialDocumentationConstraint);
        RetrievalService.CitationOutcome citationOutcome = retrievalService.discoverCitations(
                "HikariCP 7.0.5 connection pooling", officialDocumentationConstraint);

        assertEquals(
                List.of("7.0.2", "7.1.0"),
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        assertEquals(
                List.of("7.0.2", "7.1.0"),
                limitedOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        assertEquals(2, citationOutcome.citations().size());
        verify(hybridSearchService, org.mockito.Mockito.times(2))
                .searchByConstraint(
                        anyString(), eq(10), eq(List.of(hikaricp702Constraint, hikaricp710Constraint)), anyLong());
    }

    @Test
    void queryWithJavaAndDependencyGapsRetainsBothSourceFamilies() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        List<String> evidenceVersions = List.of("7.0.2", "7.1.0", "21", "25");
        List<RetrievalConstraint> evidenceConstraints = List.of(
                documentationEvidenceConstraint("hikaricp/7.0.2/api", "api-docs", "7.0.2"),
                documentationEvidenceConstraint("hikaricp/7.1.0/api", "api-docs", "7.1.0"),
                javaEvidenceConstraint(officialDocumentationConstraint, "21"),
                javaEvidenceConstraint(officialDocumentationConstraint, "25"));
        List<Document> evidenceDocuments = List.of(
                versionedDocument("hikaricp-702", "7.0.2", "hash-702", "hikaricp/7.0.2/api"),
                versionedDocument("hikaricp-710", "7.1.0", "hash-710", "hikaricp/7.1.0/api"),
                versionedDocument("java-21", "21", "hash-21"),
                versionedDocument("java-25", "25", "hash-25"));
        when(hybridSearchService.searchByConstraint(anyString(), eq(10), eq(evidenceConstraints), anyLong()))
                .thenReturn(evidenceDocuments.stream()
                        .map(document -> List.of(document))
                        .toList());
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(evidenceDocuments);

        List<Document> retrievalOutcome =
                retrievalService.retrieve("Does HikariCP 7.0.5 work with Java 22?", officialDocumentationConstraint);
        List<Document> limitedOutcome = retrievalService.retrieveWithLimit(
                "Does HikariCP 7.0.5 work with Java 22?",
                ModelConfiguration.RAG_LIMIT_CONSTRAINED,
                1_000,
                officialDocumentationConstraint);

        assertEquals(
                evidenceVersions,
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        assertEquals(
                evidenceVersions,
                limitedOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        verify(hybridSearchService, org.mockito.Mockito.times(2))
                .searchByConstraint(anyString(), eq(10), eq(evidenceConstraints), anyLong());
    }

    @Test
    void multipleDependencyGapsRetainEveryDistinctEvidenceSource() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        List<RetrievalConstraint> evidenceConstraints = List.of(
                documentationEvidenceConstraint("hikaricp/7.0.2/api", "api-docs", "7.0.2"),
                documentationEvidenceConstraint("hikaricp/7.1.0/api", "api-docs", "7.1.0"),
                documentationEvidenceConstraint("spring-ai-api-stable", "api-docs", "1.1.2"),
                documentationEvidenceConstraint("spring-ai-reference", "framework-reference", "1.1.8"));
        List<Document> evidenceDocuments = List.of(
                versionedDocument("hikaricp-702", "7.0.2", "hash-702", "hikaricp/7.0.2/api"),
                versionedDocument("hikaricp-710", "7.1.0", "hash-710", "hikaricp/7.1.0/api"),
                versionedDocument("spring-ai-112", "1.1.2", "hash-ai-112", "spring-ai-api-stable"),
                versionedDocument("spring-ai-118", "1.1.8", "hash-ai-118", "spring-ai-reference"));
        when(hybridSearchService.searchByConstraint(anyString(), eq(10), eq(evidenceConstraints), anyLong()))
                .thenReturn(evidenceDocuments.stream()
                        .map(document -> List.of(document))
                        .toList());
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(evidenceDocuments.subList(0, ModelConfiguration.RAG_LIMIT_CONSTRAINED));

        List<Document> retrievalOutcome = retrievalService.retrieveWithLimit(
                "Compare HikariCP 7.0.5 with Spring AI 1.1.5",
                ModelConfiguration.RAG_LIMIT_CONSTRAINED,
                1_000,
                officialDocumentationConstraint);

        assertEquals(
                List.of(
                        "hikaricp/7.0.2/api@7.0.2",
                        "hikaricp/7.1.0/api@7.1.0",
                        "spring-ai-api-stable@1.1.2",
                        "spring-ai-reference@1.1.8"),
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_SET_FIELD) + "@"
                                + document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
    }

    @Test
    void versionedDependencyMentionDoesNotEscapeCallerOwnedSourceScope() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint kotlinConstraint = RetrievalConstraint.forOfficialDocSets(List.of("kotlin"));
        Document kotlinDocument = versionedDocument("kotlin", "", "kotlin-hash");
        when(hybridSearchService.search(anyString(), eq(10), same(kotlinConstraint), anyLong()))
                .thenReturn(List.of(kotlinDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(kotlinDocument));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("How does this Kotlin lesson compare with HikariCP 7.0.5?", kotlinConstraint);

        assertEquals(List.of(kotlinDocument), retrievalOutcome);
        verify(hybridSearchService).search(anyString(), eq(10), eq(kotlinConstraint), anyLong());
        verify(hybridSearchService, never()).searchByConstraint(anyString(), anyInt(), anyList(), anyLong());
    }

    @Test
    void versionedDependencyGapDoesNotWidenCallerOwnedVersionScope() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint hikaricp702Constraint =
                documentationEvidenceConstraint("hikaricp/7.0.2/api", "api-docs", "7.0.2");
        Document hikaricp702Document = versionedDocument("hikaricp-702", "7.0.2", "hash-702", "hikaricp/7.0.2/api");
        when(hybridSearchService.searchByConstraint(anyString(), eq(10), eq(List.of(hikaricp702Constraint)), anyLong()))
                .thenReturn(List.of(List.of(hikaricp702Document)));
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        anyString(), eq(10), eq(List.of(hikaricp702Constraint)), anyLong()))
                .thenReturn(List.of(List.of(hikaricp702Document)));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(hikaricp702Document));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("HikariCP 7.0.5 connection pooling", hikaricp702Constraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations("HikariCP 7.0.5 connection pooling", hikaricp702Constraint);

        assertEquals(List.of(hikaricp702Document), retrievalOutcome);
        assertEquals(1, citationOutcome.citations().size());
        verify(hybridSearchService)
                .searchByConstraint(anyString(), eq(10), eq(List.of(hikaricp702Constraint)), anyLong());
    }

    @Test
    void disjointCallerVersionScopeReturnsNoAdjacentEvidenceInsteadOfBroadening() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), mock(RerankerService.class), mock(DocumentFactory.class));
        RetrievalConstraint missingExactConstraint = new RetrievalConstraint(
                List.of("7.0.5"), "official", "", "", List.of("hikaricp/7.0.2/api", "hikaricp/7.1.0/api"));

        List<Document> retrievalOutcome =
                retrievalService.retrieve("HikariCP 7.0.5 connection pooling", missingExactConstraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations("HikariCP 7.0.5 connection pooling", missingExactConstraint);

        assertEquals(List.of(), retrievalOutcome);
        assertEquals(List.of(), citationOutcome.citations());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(), anyLong());
        verify(hybridSearchService, never()).searchByConstraint(anyString(), anyInt(), anyList(), anyLong());
    }

    @Test
    void missingJavaReleaseUsesAdjacentEvidenceForExactMembersAndCitations() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java25Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "25");
        String missingReleaseQuery = "How does Java 22 java.util.List.of(E, E) work?";
        Document java21ExactOverload = exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21");
        Document java25ExactOverload = exactListOfOverloadDocument("java-25-exact", "25", "exact-hash-25");
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(missingReleaseQuery), eq(10), eq(List.of(java21Constraint, java25Constraint)), anyLong()))
                .thenReturn(List.of(List.of(java21ExactOverload), List.of(java25ExactOverload)))
                .thenReturn(List.of(List.of(java21ExactOverload), List.of(java25ExactOverload)));

        List<Document> retrievalOutcome =
                retrievalService.retrieve(missingReleaseQuery, officialDocumentationConstraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations(missingReleaseQuery, officialDocumentationConstraint);

        assertEquals(List.of(java21ExactOverload, java25ExactOverload), retrievalOutcome);
        assertEquals(
                List.of(
                        "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html",
                        "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java26Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "26");
        String exactComparisonQuery =
                "Compare Java 21 and Java 26 for java.util.List.of(E, E). Use evidence from both releases.";
        Document java21ExactOverload = exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21");
        Document java26ExactOverload = exactListOfOverloadDocument("java-26-exact", "26", "exact-hash-26");
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(exactComparisonQuery), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong()))
                .thenReturn(List.of(List.of(java21ExactOverload), List.of(java26ExactOverload)));

        List<Document> retrievalOutcome =
                retrievalService.retrieveWithLimit(exactComparisonQuery, 3, 1_000, officialDocumentationConstraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.toCitationsForQuery(exactComparisonQuery, retrievalOutcome);

        assertEquals(List.of(java21ExactOverload, java26ExactOverload), retrievalOutcome);
        assertEquals(
                List.of("21", "26"),
                retrievalOutcome.stream()
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
                        "https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/List.html"),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        verify(hybridSearchService)
                .searchDocumentationCitationsByConstraint(
                        eq(exactComparisonQuery), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
    }

    @Test
    void multiVersionExactCitationDiscoveryReturnsAuthoritativeOverloadFromEveryRequestedRelease() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, JAVA_21_RELEASE);
        RetrievalConstraint java26Constraint = javaEvidenceConstraint(officialDocumentationConstraint, JAVA_26_RELEASE);
        Document java21ExactOverload = exactListOfOverloadDocument(
                JAVA_21_EXACT_LIST_DOCUMENT_ID, JAVA_21_RELEASE, JAVA_21_EXACT_LIST_CONTENT_HASH);
        Document java26ExactOverload = exactListOfOverloadDocument(
                JAVA_26_EXACT_LIST_DOCUMENT_ID, JAVA_26_RELEASE, JAVA_26_EXACT_LIST_CONTENT_HASH);
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(EXACT_LIST_OF_QUERY),
                        eq(DOCUMENTATION_CITATION_CANDIDATE_LIMIT),
                        eq(List.of(java21Constraint, java26Constraint)),
                        anyLong()))
                .thenReturn(List.of(List.of(java21ExactOverload), List.of(java26ExactOverload)));

        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations(EXACT_LIST_OF_QUERY, officialDocumentationConstraint);

        assertEquals(
                List.of(JAVA_21_LIST_API_URL, JAVA_26_LIST_API_URL),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getUrl())
                        .toList());
        assertEquals(
                List.of(EXACT_LIST_OVERLOAD_ANCHOR, EXACT_LIST_OVERLOAD_ANCHOR),
                citationOutcome.citations().stream()
                        .map(citation -> citation.getAnchor())
                        .toList());
        assertEquals(0, citationOutcome.failedConversionCount());
        verify(hybridSearchService)
                .searchDocumentationCitationsByConstraint(
                        eq(EXACT_LIST_OF_QUERY),
                        eq(DOCUMENTATION_CITATION_CANDIDATE_LIMIT),
                        eq(List.of(java21Constraint, java26Constraint)),
                        anyLong());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        when(hybridSearchService.search(anyString(), anyInt(), same(kotlinConstraint), anyLong()))
                .thenReturn(List.of(kotlinDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(kotlinDocument));

        List<Document> retrievalOutcome = retrievalService.retrieve(kotlinQuery, kotlinConstraint);

        assertEquals(List.of(kotlinDocument), retrievalOutcome);
        verify(hybridSearchService, never())
                .searchDocumentationCitations(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
    }

    @Test
    void virtualThreadStartChainUsesItsCanonicalBuilderDeclaration() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        String chainedInvocationQuery = "Using Java 21, explain Thread.ofVirtual().start(Runnable).";
        Document virtualThreadDocument = exactThreadBuilderStartDocument();
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(chainedInvocationQuery), anyInt(), eq(List.of(java21Constraint)), anyLong()))
                .thenReturn(List.of(List.of(virtualThreadDocument)));

        List<Document> retrievalOutcome =
                retrievalService.retrieve(chainedInvocationQuery, officialDocumentationConstraint);
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.toCitationsForQuery(chainedInvocationQuery, retrievalOutcome);

        assertEquals(List.of(virtualThreadDocument), retrievalOutcome);
        assertEquals(
                "start(java.lang.Runnable)",
                citationOutcome.citations().getFirst().getAnchor());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        when(hybridSearchService.search(anyString(), anyInt(), same(unconstrained), anyLong()))
                .thenReturn(List.of(projectDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(projectDocument));

        List<Document> retrievalOutcome = retrievalService.retrieve(projectQuery, unconstrained);

        assertEquals(List.of(projectDocument), retrievalOutcome);
        verify(hybridSearchService, never())
                .searchDocumentationCitations(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
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
                exactListOfOverloadDocument("java-26-exact", "26", "exact-hash-26"),
                exactListOfOverloadDocument("java-25-exact", "25", "exact-hash-25"));
        when(hybridSearchService.searchDocumentationCitations(
                        eq(exactQuery), anyInt(), same(officialDocumentationConstraint), anyLong()))
                .thenReturn(exactOverloadDocuments);

        List<Document> retrievalOutcome = retrievalService.retrieve(exactQuery, officialDocumentationConstraint);

        assertEquals(exactOverloadDocuments.subList(0, 2), retrievalOutcome);
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
    }

    @Test
    void exactMultiVersionOverloadUsesAvailableEvidenceWhenOneReleaseHasNoHit() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java26Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "26");
        String exactComparisonQuery = "Compare Java 21 and Java 26 for java.util.List.of(E, E).";
        when(hybridSearchService.searchDocumentationCitationsByConstraint(
                        eq(exactComparisonQuery), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong()))
                .thenReturn(List.of(
                        List.of(exactListOfOverloadDocument("java-21-exact", "21", "exact-hash-21")), List.of()));

        List<Document> retrievalOutcome =
                retrievalService.retrieve(exactComparisonQuery, officialDocumentationConstraint);

        assertEquals(
                List.of("21"),
                retrievalOutcome.stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.DOC_VERSION_FIELD))
                        .toList());
        verify(hybridSearchService, never()).search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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
        RetrievalConstraint java21Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "21");
        RetrievalConstraint java26Constraint = javaEvidenceConstraint(officialDocumentationConstraint, "26");
        List<Document> java21Documents = List.of(
                versionedDocument("java-21-a", "21", "hash-21-a"),
                versionedDocument("java-21-b", "21", "hash-21-b"),
                versionedDocument("java-21-c", "21", "hash-21-c"),
                versionedDocument("java-21-d", "21", "hash-21-d"),
                versionedDocument("java-21-e", "21", "hash-21-e"));
        Document java26Document = versionedDocument("java-26", "26", "hash-26");
        when(hybridSearchService.searchByConstraint(
                        anyString(), eq(10), eq(List.of(java21Constraint, java26Constraint)), anyLong()))
                .thenReturn(List.of(java21Documents, List.of(java26Document)));
        List<Document> rerankedDocuments = new java.util.ArrayList<>(java21Documents);
        rerankedDocuments.add(java26Document);
        when(rerankerService.rerank(anyString(), anyList(), eq(6), anyLong())).thenReturn(rerankedDocuments);

        List<Document> limitedOutcome = retrievalService.retrieveWithLimit(
                "Compare Java 21 and Java 26 collections", 3, 1_000, officialDocumentationConstraint);

        assertEquals(3, limitedOutcome.size());
        assertEquals(
                List.of("21", "21", "26"),
                limitedOutcome.stream()
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
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        List<Document> retrievedDocuments = List.of(
                versionedDocument("document-a", "", "hash-a"),
                versionedDocument("document-b", "", "hash-b"),
                versionedDocument("document-c", "", "hash-c"),
                versionedDocument("document-d", "", "hash-d"),
                versionedDocument("document-e", "", "hash-e"),
                versionedDocument("document-f", "", "hash-f"));
        when(hybridSearchService.search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(retrievedDocuments);
        when(rerankerService.rerank(anyString(), anyList(), eq(6), anyLong())).thenReturn(retrievedDocuments);

        List<Document> limitedOutcome =
                retrievalService.retrieveWithLimit("Explain Java strings", 3, 1_000, officialDocumentationConstraint);

        assertEquals(3, limitedOutcome.size());
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
                .searchDocumentationCitations(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
    }

    @Test
    void citationDiscoveryPropagatesHybridFailuresWithoutRerankerFallback() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        HybridSearchPartialFailureException.CollectionSearchFailure collectionFailure =
                new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-docs", "Timeout", "5s", HybridSearchPartialFailureException.FailureDisposition.TRANSIENT);
        when(hybridSearchService.searchDocumentationCitations(
                        anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenThrow(new HybridSearchPartialFailureException("collection failure", List.of(collectionFailure)));

        assertThrows(
                HybridSearchPartialFailureException.class,
                () -> retrievalService.discoverCitations("Java strings", guidedConstraint));

        verify(rerankerService, never()).rerank(anyString(), anyList(), anyInt(), anyLong());
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

    private static void assertGenericOfficialMemberQuery(String learnerQuery) {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        Document genericDocument = versionedDocument("generic-member", "", "generic-member-hash");
        when(hybridSearchService.search(eq(learnerQuery), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of(genericDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(List.of(genericDocument));

        List<Document> retrievalOutcome = retrievalService.retrieve(learnerQuery, officialDocumentationConstraint);

        assertEquals(List.of(genericDocument), retrievalOutcome);
        verify(hybridSearchService, never())
                .searchDocumentationCitations(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong());
    }

    private static RetrievalConstraint defaultJavaApiBroadOfficialConstraint() {
        return RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES.stream()
                .filter(sourceIdentity -> !JAVA_API_DOCUMENTATION_SOURCE_IDENTITIES.contains(sourceIdentity)
                        || DEFAULT_JAVA_API_DOCUMENTATION_SOURCE_IDENTITY.equals(sourceIdentity))
                .toList());
    }

    private static RetrievalConstraint javaEvidenceConstraint(
            RetrievalConstraint retrievalConstraint, String evidenceVersion) {
        String documentationSet = javaApiSource(evidenceVersion).relativeMirrorPath();
        return new RetrievalConstraint(
                List.of(evidenceVersion),
                retrievalConstraint.sourceKind(),
                DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE,
                "oracle",
                List.of(documentationSet));
    }

    private static RetrievalConstraint documentationEvidenceConstraint(
            String documentationSet, String documentType, String documentVersion) {
        return new RetrievalConstraint(
                List.of(documentVersion), "official", documentType, documentationSet, List.of(documentationSet));
    }

    private static Document versionedDocument(String documentId, String documentVersion, String contentHash) {
        String documentationSet =
                documentVersion.isBlank() ? "" : javaApiSource(documentVersion).relativeMirrorPath();
        return versionedDocument(documentId, documentVersion, contentHash, documentationSet);
    }

    private static Document versionedDocument(
            String documentId, String documentVersion, String contentHash, String documentationSet) {
        return Document.builder()
                .id(documentId)
                .text("Java " + documentVersion + " documentation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.DOC_SET_FIELD, documentationSet)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, "https://docs.example.test/" + documentVersion + "/")
                .build();
    }

    private static Document versionedCitationDocument(String documentId, String documentVersion, String contentHash) {
        String documentationSet = javaApiSource(documentVersion).relativeMirrorPath();
        return Document.builder()
                .id(documentId)
                .text("Java " + documentVersion + " citation")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.DOC_SET_FIELD, documentationSet)
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example.test/" + documentVersion + "/List.html")
                .build();
    }

    private static Document exactListOfOverloadDocument(String documentId, String documentVersion, String contentHash) {
        DocsSourceRegistry.JavaApiDocumentationSource documentationSource = javaApiSource(documentVersion);
        return Document.builder()
                .id(documentId)
                .text(EXACT_LIST_OVERLOAD_TEXT)
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, documentVersion)
                .metadata(QdrantPayloadFieldSchema.DOC_SET_FIELD, documentationSource.relativeMirrorPath())
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, contentHash)
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        documentationSource.remoteBaseUrl() + "java.base/java/util/" + JAVA_LIST_API_PAGE)
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, JAVA_UTIL_PACKAGE)
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, JAVA_LIST_API_PAGE)
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, EXACT_LIST_OVERLOAD_ANCHOR)
                .build();
    }

    private static Document exactThreadBuilderStartDocument() {
        DocsSourceRegistry.JavaApiDocumentationSource documentationSource = javaApiSource("21");
        return Document.builder()
                .id("java-21-thread-builder-start")
                .text("Thread start(Runnable task) Creates a new Thread and schedules it to execute")
                .metadata(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, "21")
                .metadata(QdrantPayloadFieldSchema.DOC_SET_FIELD, documentationSource.relativeMirrorPath())
                .metadata(QdrantPayloadFieldSchema.HASH_FIELD, "java-21-thread-builder-start-hash")
                .metadata(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        documentationSource.remoteBaseUrl() + "java.base/java/lang/Thread.Builder.html")
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.lang")
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, "Thread.Builder.html")
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, "start(java.lang.Runnable)")
                .build();
    }

    private static DocsSourceRegistry.JavaApiDocumentationSource javaApiSource(String javaRelease) {
        return DocsSourceRegistry.javaApiDocumentationSources().stream()
                .filter(candidateSource -> javaRelease.equals(candidateSource.javaRelease()))
                .findFirst()
                .orElseThrow();
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

    private static Document apiDocumentationMemberCitationCandidate(
            String documentId, String documentText, String packageName, String pageFilename, String memberAnchor) {
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(QdrantPayloadFieldSchema.URL_FIELD, javaApiPageUrl(packageName, pageFilename))
                .metadata(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE)
                .metadata(QdrantPayloadFieldSchema.PACKAGE_FIELD, packageName)
                .metadata(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, pageFilename)
                .metadata(QdrantPayloadFieldSchema.ANCHOR_FIELD, memberAnchor)
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
        when(hybridSearchService.search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong()))
                .thenReturn(retrievalCandidates);
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenAnswer(rerankerInvocation -> {
                    List<Document> deduplicatedCandidates = rerankerInvocation.getArgument(1);
                    return deduplicatedCandidates;
                });

        List<Document> retrievalOutcome = retrievalService.retrieve("Java string basics");
        RetrievalService.CitationOutcome citationOutcome = retrievalService.toCitations(retrievalOutcome);

        assertEquals(List.of(urlOnlyDocument, firstJavadocChunk, secondJavadocChunkWithDistinctHash), retrievalOutcome);
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
        when(hybridSearchService.search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong()))
                .thenReturn(retrievalCandidates);
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenAnswer(rerankerInvocation -> {
                    List<Document> deduplicatedCandidates = rerankerInvocation.getArgument(1);
                    return deduplicatedCandidates;
                });

        List<Document> retrievalOutcome = retrievalService.retrieve("Local documentation");
        RetrievalService.CitationOutcome citationOutcome = retrievalService.toCitations(retrievalOutcome);
        String redactedLocalCitationUrl = DocsSourceRegistry.normalizeDocUrl(firstUnmappedLocalUrl);

        assertEquals(retrievalCandidates, retrievalOutcome);
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
                new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-docs", "Timeout", "5s", HybridSearchPartialFailureException.FailureDisposition.TRANSIENT);
        when(hybridSearchService.search(anyString(), anyInt(), any(RetrievalConstraint.class), anyLong()))
                .thenThrow(new HybridSearchPartialFailureException("collection failure", List.of(collectionFailure)));

        assertThrows(HybridSearchPartialFailureException.class, () -> retrievalService.retrieve("Java stream basics"));
    }

    @Test
    void sharesOneStageDeadlineAcrossSearchAndRerankHops() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        Document stageDeadlineDocument = versionedDocument("stage-deadline", "", "stage-deadline-hash");
        long testStartNanos = System.nanoTime();
        AtomicLong capturedStageDeadlineNanos = new AtomicLong();
        when(hybridSearchService.search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenAnswer(searchInvocation -> {
                    capturedStageDeadlineNanos.set(searchInvocation.getArgument(3));
                    return List.of(stageDeadlineDocument);
                });
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenAnswer(rerankerInvocation -> {
                    assertEquals(capturedStageDeadlineNanos.get(), (long) rerankerInvocation.getArgument(3));
                    return List.of(stageDeadlineDocument);
                });

        retrievalService.retrieve("Java records", guidedConstraint);

        long stageBudgetElapsedNanos = capturedStageDeadlineNanos.get() - testStartNanos;
        assertTrue(stageBudgetElapsedNanos > 0);
        assertTrue(stageBudgetElapsedNanos
                <= RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT
                        .plus(STAGE_DEADLINE_ASSERTION_TOLERANCE)
                        .toNanos());
        assertTrue(stageBudgetElapsedNanos >= RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos());
    }

    @Test
    void rejectsRerankerOutcomeThatReturnsAfterTheCallerDeadline() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        RerankerService rerankerService = mock(RerankerService.class);
        RetrievalService retrievalService = new RetrievalService(
                hybridSearchService, new AppProperties(), rerankerService, mock(DocumentFactory.class));
        RetrievalConstraint guidedConstraint =
                RetrievalConstraint.forOfficialDocSets(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
        RetrievalConstraint expectedScopedConstraint = defaultJavaApiBroadOfficialConstraint();
        Document deadlineDocument = versionedDocument("deadline-expired", "", "deadline-expired-hash");
        when(hybridSearchService.search(anyString(), anyInt(), eq(expectedScopedConstraint), anyLong()))
                .thenReturn(List.of(deadlineDocument));
        when(rerankerService.rerank(anyString(), anyList(), anyInt(), anyLong()))
                .thenAnswer(rerankerInvocation -> {
                    long callerDeadlineNanos = rerankerInvocation.getArgument(3);
                    while (System.nanoTime() <= callerDeadlineNanos) {
                        Thread.onSpinWait();
                    }
                    return List.of(deadlineDocument);
                });
        long callerDeadlineNanos = System.nanoTime() + Duration.ofMillis(5).toNanos();

        RerankingFailureException deadlineFailure = assertThrows(
                RerankingFailureException.class,
                () -> retrievalService.retrieve(
                        "Java records", guidedConstraint, ignoredNotice -> {}, callerDeadlineNanos));

        assertInstanceOf(TimeoutException.class, deadlineFailure.getCause());
    }
}
