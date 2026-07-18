package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
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
 * list before citation URL deduplication and truncation, so exact Javadoc type pages win over
 * accidental lexical matches without an additional RPC, embedding request, or model call.</p>
 */
final class CitationCandidateRanker {

    private CitationCandidateRanker() {}

    /**
     * Orders a Qdrant candidate list by explicit Javadoc type-page and method-declaration evidence.
     *
     * <p>When the query names no Java API selector, this returns the original Qdrant order. Equal
     * relevance tiers retain that order deterministically.</p>
     *
     * @param citationQuery learner query used for sparse citation retrieval
     * @param citationCandidates Qdrant-ranked citation candidates
     * @return immutable candidate list ordered for citation conversion
     */
    static List<Document> orderForCitationQuery(String citationQuery, List<Document> citationCandidates) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        Objects.requireNonNull(citationCandidates, "citationCandidates");
        return JavaApiMethodSelector.fromQuery(citationQuery)
                .map(selector -> reorderForSelector(selector, citationCandidates))
                .orElseGet(() -> List.copyOf(citationCandidates));
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
            return 3;
        }
        boolean matchesTypePage = matchesTypePage(selector, citationCandidate);
        boolean hasMethodDeclaration = hasMethodDeclarationEvidence(selector, citationCandidate);
        if (matchesTypePage && hasMethodDeclaration) {
            return 0;
        }
        if (matchesTypePage) {
            return 1;
        }
        if (hasMethodDeclaration) {
            return 2;
        }
        return 3;
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
        return selector.matchesJavadocPath(documentPath);
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

    private static boolean hasIdentifierBoundaryBefore(String text, int index) {
        return index == 0 || !Character.isJavaIdentifierPart(text.charAt(index - 1));
    }

    private static boolean hasIdentifierBoundaryAfter(String text, int index) {
        return index == text.length() || !Character.isJavaIdentifierPart(text.charAt(index));
    }

    private static int skipWhitespace(String text, int startIndex) {
        int currentIndex = startIndex;
        while (currentIndex < text.length() && Character.isWhitespace(text.charAt(currentIndex))) {
            currentIndex++;
        }
        return currentIndex;
    }

    private record IndexedCitationCandidate(Document citationCandidate, int originalPosition, int relevanceTier) {}
}
