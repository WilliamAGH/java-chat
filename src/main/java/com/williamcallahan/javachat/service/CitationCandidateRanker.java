package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.service.ingestion.JavaPackageExtractor;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;

/**
 * Orders sparse citation candidates using explicit Java API selector evidence.
 *
 * <p>Qdrant remains the sole retrieval authority. This ranker only reorders its bounded candidate
 * list before citation URL deduplication and truncation. When one learner selector includes an
 * unambiguous Java type signature, it retains only existing members whose stored source anchor
 * exactly matches; it never derives an overload from retrieved text.</p>
 */
final class CitationCandidateRanker {

    private static final int TYPE_AND_METHOD_RELEVANCE_TIER = 0;
    private static final int TYPE_PAGE_RELEVANCE_TIER = 1;
    private static final int METHOD_DECLARATION_RELEVANCE_TIER = 2;
    private static final int NON_MATCHING_RELEVANCE_TIER = 3;

    private CitationCandidateRanker() {}

    /**
     * Orders a Qdrant candidate list by explicit Javadoc type-page and method-declaration evidence.
     *
     * <p>A sole exact source-anchor signature is an eligibility gate over persisted Java API
     * metadata. Value expressions, incomplete signatures, and multi-selector queries use broad
     * relevance tiers, whose ties retain Qdrant order deterministically.</p>
     *
     * @param citationQuery learner query used for sparse citation retrieval
     * @param citationCandidates Qdrant-ranked citation candidates
     * @return immutable candidate list ordered for citation conversion
     */
    static List<Document> orderForCitationQuery(String citationQuery, List<Document> citationCandidates) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        Objects.requireNonNull(citationCandidates, "citationCandidates");
        return JavaApiMethodSelector.uniqueExactOverloadFromQuery(citationQuery)
                .map(selector -> exactOverloadCandidates(selector, citationCandidates))
                .orElseGet(() -> JavaApiMethodSelector.fromQuery(citationQuery)
                        .map(selector -> reorderForSelector(selector, citationCandidates))
                        .orElseGet(() -> List.copyOf(citationCandidates)));
    }

    /** Narrows prompt context to authoritative source-anchor matches for a sole exact overload query. */
    static List<Document> selectPromptContextForCitationQuery(String citationQuery, List<Document> promptDocuments) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        Objects.requireNonNull(promptDocuments, "promptDocuments");
        return JavaApiMethodSelector.uniqueExactOverloadFromQuery(citationQuery)
                .map(selector -> exactOverloadCandidates(selector, promptDocuments))
                .orElseGet(() -> List.copyOf(promptDocuments));
    }

    private static List<Document> exactOverloadCandidates(
            JavaApiMethodSelector selector, List<Document> citationCandidates) {
        String expectedAnchor = selector.exactOverloadAnchor()
                .orElseThrow(() -> new IllegalArgumentException("Exact overload candidates require a source anchor"));
        List<Document> exactCandidates = new ArrayList<>(citationCandidates.size());
        for (int candidatePosition = 0; candidatePosition < citationCandidates.size(); candidatePosition++) {
            Document citationCandidate = Objects.requireNonNull(
                    citationCandidates.get(candidatePosition), "citationCandidates[" + candidatePosition + "]");
            if (matchesExactOverloadMetadata(selector, expectedAnchor, citationCandidate)) {
                exactCandidates.add(citationCandidate);
            }
        }
        return List.copyOf(exactCandidates);
    }

    private static List<Document> reorderForSelector(
            JavaApiMethodSelector selector, List<Document> citationCandidates) {
        List<IndexedCitationCandidate> indexedCitationCandidates = new ArrayList<>(citationCandidates.size());
        for (int candidatePosition = 0; candidatePosition < citationCandidates.size(); candidatePosition++) {
            Document citationCandidate = Objects.requireNonNull(
                    citationCandidates.get(candidatePosition), "citationCandidates[" + candidatePosition + "]");
            indexedCitationCandidates.add(new IndexedCitationCandidate(
                    citationCandidate, candidatePosition, relevanceTier(selector, citationCandidate)));
        }
        return indexedCitationCandidates.stream()
                .sorted(Comparator.comparingInt(IndexedCitationCandidate::relevanceTier)
                        .thenComparingInt(IndexedCitationCandidate::originalPosition))
                .map(IndexedCitationCandidate::citationCandidate)
                .toList();
    }

    private static int relevanceTier(JavaApiMethodSelector selector, Document citationCandidate) {
        if (!hasApiDocumentationType(citationCandidate)) {
            return NON_MATCHING_RELEVANCE_TIER;
        }
        boolean matchesTypePage = matchesTypePage(selector, citationCandidate);
        boolean hasMethodDeclaration = hasMethodDeclarationEvidence(selector, citationCandidate);
        if (matchesTypePage && hasMethodDeclaration) {
            return TYPE_AND_METHOD_RELEVANCE_TIER;
        }
        if (matchesTypePage) {
            return TYPE_PAGE_RELEVANCE_TIER;
        }
        if (hasMethodDeclaration) {
            return METHOD_DECLARATION_RELEVANCE_TIER;
        }
        return NON_MATCHING_RELEVANCE_TIER;
    }

    private static boolean hasApiDocumentationType(Document citationCandidate) {
        return DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE.equals(
                citationCandidate.getMetadata().get(QdrantPayloadFieldSchema.DOC_TYPE_FIELD));
    }

    private static boolean matchesTypePage(JavaApiMethodSelector selector, Document citationCandidate) {
        Object rawUrl = citationCandidate.getMetadata().get(QdrantPayloadFieldSchema.URL_FIELD);
        if (!(rawUrl instanceof String sourceUrl) || sourceUrl.isBlank()) {
            return false;
        }
        String documentPath = URI.create(sourceUrl).getPath();
        if (documentPath == null || documentPath.isBlank()) {
            return false;
        }
        String candidatePackageName = JavaPackageExtractor.extractJavaApiPackage(sourceUrl);
        return selector.matchesJavadocPath(documentPath, candidatePackageName);
    }

    private static boolean matchesExactOverloadMetadata(
            JavaApiMethodSelector selector, String expectedAnchor, Document citationCandidate) {
        if (!matchesSelectorTypePageMetadata(selector, citationCandidate)) {
            return false;
        }
        Object rawAnchor = citationCandidate.getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD);
        return rawAnchor instanceof String candidateAnchor && expectedAnchor.equals(candidateAnchor);
    }

    private static boolean matchesSelectorTypePageMetadata(JavaApiMethodSelector selector, Document citationCandidate) {
        if (!hasApiDocumentationType(citationCandidate)) {
            return false;
        }
        if (!selector.packageName().isBlank()) {
            Object rawPackageName = citationCandidate.getMetadata().get(QdrantPayloadFieldSchema.PACKAGE_FIELD);
            if (!(rawPackageName instanceof String candidatePackageName)
                    || !selector.packageName().equals(candidatePackageName)) {
                return false;
            }
        }
        Object rawTypePage = citationCandidate.getMetadata().get(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD);
        return rawTypePage instanceof String candidateTypePage
                && selector.typePageFileName().equals(candidateTypePage);
    }

    private static boolean hasMethodDeclarationEvidence(JavaApiMethodSelector selector, Document citationCandidate) {
        String documentText = citationCandidate.getText();
        if (documentText == null || documentText.isBlank()) {
            return false;
        }

        String methodName = selector.methodName();
        int lastMethodStartIndex = documentText.length() - methodName.length();
        for (int methodStartIndex = 0; methodStartIndex <= lastMethodStartIndex; methodStartIndex++) {
            if (!hasIdentifierBoundaryBefore(documentText, methodStartIndex)
                    || !documentText.regionMatches(methodStartIndex, methodName, 0, methodName.length())) {
                continue;
            }
            int methodEndIndex = methodStartIndex + methodName.length();
            if (!hasIdentifierBoundaryAfter(documentText, methodEndIndex)) {
                continue;
            }
            int followingIndex = skipWhitespace(documentText, methodEndIndex);
            if (followingIndex < documentText.length() && documentText.charAt(followingIndex) == '(') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIdentifierBoundaryBefore(String documentText, int index) {
        return index == 0 || !Character.isJavaIdentifierPart(documentText.charAt(index - 1));
    }

    private static boolean hasIdentifierBoundaryAfter(String documentText, int index) {
        return index == documentText.length() || !Character.isJavaIdentifierPart(documentText.charAt(index));
    }

    private static int skipWhitespace(String documentText, int startIndex) {
        int currentIndex = startIndex;
        while (currentIndex < documentText.length() && Character.isWhitespace(documentText.charAt(currentIndex))) {
            currentIndex++;
        }
        return currentIndex;
    }

    private record IndexedCitationCandidate(Document citationCandidate, int originalPosition, int relevanceTier) {}
}
