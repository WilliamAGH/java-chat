package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import com.williamcallahan.javachat.util.QueryVersionExtractor.VersionFilterPatterns;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Retrieves and reranks context documents for RAG queries and converts them into citation-ready metadata.
 *
 * <p>This implementation performs hybrid retrieval per Qdrant collection (dense + sparse) and
 * fails fast on any dependency failures. It does not fall back to secondary retrieval paths.</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final int DEBUG_FIRST_DOC_PREVIEW_LENGTH = 200;
    private static final int CITATION_SNIPPET_MAX_LENGTH = 500;
    private static final double TRUNCATION_BREAK_THRESHOLD = 0.8;

    private static final String METADATA_URL = "url";
    private static final String METADATA_TITLE = "title";
    private static final String METADATA_PACKAGE = "package";
    private static final String METADATA_HASH = "hash";
    private static final String METADATA_DOC_TYPE = "docType";
    private static final String DOCUMENT_TYPE_API_DOCS = "api-docs";
    private static final String FILE_URL_PREFIX = "file://";
    private static final char URL_FRAGMENT_DELIMITER = '#';

    private final HybridSearchService hybridSearchService;
    private final AppProperties appProperties;
    private final RerankerService rerankerService;
    private final DocumentFactory documentFactory;

    /**
     * Creates a retrieval service backed by gRPC hybrid search with RRF fusion and a reranker.
     *
     * @param hybridSearchService gRPC-based hybrid search across all collections
     * @param appProperties application configuration
     * @param rerankerService reranker for result ordering
     * @param documentFactory document factory for metadata preservation
     */
    public RetrievalService(
            HybridSearchService hybridSearchService,
            AppProperties appProperties,
            RerankerService rerankerService,
            DocumentFactory documentFactory) {
        this.hybridSearchService = hybridSearchService;
        this.appProperties = appProperties;
        this.rerankerService = rerankerService;
        this.documentFactory = documentFactory;
    }

    /**
     * Diagnostic notice describing retrieval failures.
     *
     * @param summary short human-readable summary
     * @param details detailed diagnostics suitable for UI display
     */
    public static record RetrievalNotice(String summary, String details) {
        public RetrievalNotice {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("Summary cannot be null or blank");
            }
            details = details == null ? "" : details;
        }
    }

    /**
     * Outcome of a retrieval request, including documents and diagnostic notices.
     *
     * <p>Notices are informational only; this service does not swallow dependency failures.</p>
     *
     * @param documents retrieved documents
     * @param notices diagnostic notices for UI consumption
     */
    public static record RetrievalOutcome(List<Document> documents, List<RetrievalNotice> notices) {
        public RetrievalOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    /**
     * Retrieves documents for a query using hybrid retrieval and reranking.
     */
    public List<Document> retrieve(String query) {
        return retrieveOutcome(query).documents();
    }

    /**
     * Retrieves documents for a query within the caller-owned metadata constraint.
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return retrieved and reranked documents
     */
    public List<Document> retrieve(String query, RetrievalConstraint retrievalConstraint) {
        return retrieveOutcome(query, retrievalConstraint).documents();
    }

    /**
     * Discovers static citations from one sparse official-documentation query.
     *
     * <p>Candidate documents are converted and deduplicated by their final citation URL and anchor
     * before the configured citation limit is applied. Chat-answer context retrieval continues to
     * use {@link #retrieve(String)} and its configured hybrid and reranking pipeline.</p>
     *
     * @param query citation-discovery query
     * @param retrievalConstraint exact server-side constraint for the citation sources
     * @return limited citations plus every candidate conversion failure
     */
    public CitationOutcome discoverCitations(String query, RetrievalConstraint retrievalConstraint) {
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        if (query == null || query.isBlank()) {
            return new CitationOutcome(List.of(), 0);
        }
        int citationLimit = appProperties.getRag().getSearchCitations();
        if (citationLimit <= 0) {
            return new CitationOutcome(List.of(), 0);
        }
        int citationCandidateLimit = Math.max(appProperties.getRag().getSearchTopK(), citationLimit);
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);
        RetrievalConstraint combinedRetrievalConstraint = combineWithVersionFilter(retrievalConstraint, versionFilter);
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);
        HybridSearchService.SearchOutcome citationSearchOutcome =
                hybridSearchService.searchDocumentationCitationsOutcome(
                        boostedQuery, citationCandidateLimit, combinedRetrievalConstraint);
        CitationOutcome candidateCitationOutcome = toCitations(citationSearchOutcome.documents());
        List<Citation> limitedCitations = candidateCitationOutcome.citations().stream()
                .limit(citationLimit)
                .toList();
        return new CitationOutcome(limitedCitations, candidateCitationOutcome.failedConversionCount());
    }

    /**
     * Retrieves documents and diagnostic notices for a query.
     */
    public RetrievalOutcome retrieveOutcome(String query) {
        if (query == null || query.isBlank()) {
            return new RetrievalOutcome(List.of(), List.of());
        }
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);
        RetrievalConstraint retrievalConstraint = toRetrievalConstraint(versionFilter);
        return retrieveOutcome(query, retrievalConstraint, versionFilter);
    }

    /**
     * Retrieves documents and notices within the caller-owned metadata constraint.
     *
     * <p>Feature-specific source scopes are retained while any query-derived Java version is added
     * to the same server-side Qdrant filter.</p>
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return retrieval outcome with documents and notices
     */
    public RetrievalOutcome retrieveOutcome(String query, RetrievalConstraint retrievalConstraint) {
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        if (query == null || query.isBlank()) {
            return new RetrievalOutcome(List.of(), List.of());
        }
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);
        RetrievalConstraint combinedRetrievalConstraint = combineWithVersionFilter(retrievalConstraint, versionFilter);
        return retrieveOutcome(query, combinedRetrievalConstraint, versionFilter);
    }

    private static RetrievalConstraint combineWithVersionFilter(
            RetrievalConstraint retrievalConstraint, Optional<VersionFilterPatterns> versionFilter) {
        return versionFilter
                .map(filterPatterns -> retrievalConstraint.withDocVersion(filterPatterns.versionNumber()))
                .orElse(retrievalConstraint);
    }

    private RetrievalOutcome retrieveOutcome(
            String query, RetrievalConstraint retrievalConstraint, Optional<VersionFilterPatterns> versionFilter) {
        CandidateRetrieval candidateRetrieval = retrieveCandidates(query, retrievalConstraint, versionFilter);

        List<Document> reranked = rerankerService.rerank(
                query, candidateRetrieval.documents(), appProperties.getRag().getSearchReturnK());

        if (!reranked.isEmpty()) {
            Map<String, ?> firstDocMetadata = reranked.get(0).getMetadata();
            int metadataSize = firstDocMetadata.size();
            String firstDocumentText =
                    Optional.ofNullable(reranked.get(0).getText()).orElse("");
            int previewLength = Math.min(DEBUG_FIRST_DOC_PREVIEW_LENGTH, firstDocumentText.length());
            log.debug("First doc metadata size: {}", metadataSize);
            log.debug("First doc content preview length: {}", previewLength);
        }
        return new RetrievalOutcome(reranked, candidateRetrieval.notices());
    }

    private CandidateRetrieval retrieveCandidates(
            String query, RetrievalConstraint retrievalConstraint, Optional<VersionFilterPatterns> versionFilter) {
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);
        int baseTopK = Math.max(1, appProperties.getRag().getSearchTopK());
        HybridSearchService.SearchOutcome searchOutcome =
                hybridSearchService.searchOutcome(boostedQuery, baseTopK, retrievalConstraint);
        List<Document> filtered = retrievalConstraint.hasServerSideConstraint()
                ? searchOutcome.documents()
                : applyVersionFilterIfPresent(versionFilter, searchOutcome.documents());
        List<Document> deduplicatedCandidates = deduplicateByContentHashThenHashlessCanonicalUrl(filtered);
        List<RetrievalNotice> retrievalNotices = searchOutcome.notices().stream()
                .map(searchNotice -> new RetrievalNotice(searchNotice.summary(), searchNotice.details()))
                .toList();
        return new CandidateRetrieval(deduplicatedCandidates, retrievalNotices);
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     */
    public RetrievalOutcome retrieveWithLimitOutcome(String query, int maxDocuments, int maxTokensPerDocument) {
        return limitRetrievalOutcome(retrieveOutcome(query), maxDocuments, maxTokensPerDocument);
    }

    /**
     * Retrieves constrained documents while capping document count and per-document token budget.
     *
     * @param query retrieval query
     * @param maxDocuments maximum number of documents to retain
     * @param maxTokensPerDocument maximum estimated tokens retained per document
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return constrained, truncated retrieval outcome
     */
    public RetrievalOutcome retrieveWithLimitOutcome(
            String query, int maxDocuments, int maxTokensPerDocument, RetrievalConstraint retrievalConstraint) {
        return limitRetrievalOutcome(retrieveOutcome(query, retrievalConstraint), maxDocuments, maxTokensPerDocument);
    }

    private RetrievalOutcome limitRetrievalOutcome(
            RetrievalOutcome outcome, int maxDocuments, int maxTokensPerDocument) {
        List<Document> documents = outcome.documents();
        if (documents.isEmpty()) {
            return outcome;
        }
        List<Document> truncatedDocuments = documents.stream()
                .limit(Math.max(1, maxDocuments))
                .map(document -> truncateDocumentToTokenLimit(document, maxTokensPerDocument))
                .toList();
        return new RetrievalOutcome(truncatedDocuments, outcome.notices());
    }

    /**
     * Retrieves documents while capping document count and per-document token budget.
     */
    public List<Document> retrieveWithLimit(String query, int maxDocuments, int maxTokensPerDocument) {
        return retrieveWithLimitOutcome(query, maxDocuments, maxTokensPerDocument)
                .documents();
    }

    private List<Document> applyVersionFilterIfPresent(
            Optional<VersionFilterPatterns> versionFilter, List<Document> documents) {
        if (versionFilter.isEmpty()) {
            return documents;
        }
        VersionFilterPatterns filter = versionFilter.get();
        List<Document> matched = documents.stream()
                .filter(document -> filter.matchesMetadata(
                        stringMetadataValue(document.getMetadata(), METADATA_URL),
                        stringMetadataValue(document.getMetadata(), METADATA_TITLE)))
                .toList();
        return matched.isEmpty() ? documents : matched;
    }

    private static RetrievalConstraint toRetrievalConstraint(Optional<VersionFilterPatterns> versionFilter) {
        if (versionFilter.isEmpty()) {
            return RetrievalConstraint.none();
        }
        String versionNumber = versionFilter.get().versionNumber();
        return RetrievalConstraint.forDocVersion(versionNumber);
    }

    /** Holds hybrid-ranked candidates before the operation-specific final ordering step. */
    private record CandidateRetrieval(List<Document> documents, List<RetrievalNotice> notices) {
        private CandidateRetrieval {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    private List<Document> deduplicateByContentHashThenHashlessCanonicalUrl(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Set<String> retainedContentHashes = new HashSet<>();
        Set<String> retainedHashlessCanonicalUrls = new HashSet<>();
        List<Document> deduplicatedDocuments = new ArrayList<>(documents.size());
        int unidentifiedDocumentCount = 0;
        for (Document document : documents) {
            String contentHash = stringMetadataValue(document.getMetadata(), METADATA_HASH);
            if (!contentHash.isBlank()) {
                if (!retainedContentHashes.add(contentHash)) {
                    continue;
                }
            } else {
                String documentUrl = stringMetadataValue(document.getMetadata(), METADATA_URL)
                        .trim();
                if (!documentUrl.isBlank()) {
                    String canonicalDocumentUrl = documentUrl.startsWith(FILE_URL_PREFIX)
                            ? DocsSourceRegistry.resolveLocalPath(documentUrl.substring(FILE_URL_PREFIX.length()))
                                    .map(DocsSourceRegistry::canonicalizeHttpDocUrl)
                                    .orElse(documentUrl)
                            : DocsSourceRegistry.normalizeDocUrl(documentUrl);
                    if (!retainedHashlessCanonicalUrls.add(canonicalDocumentUrl)) {
                        continue;
                    }
                } else {
                    unidentifiedDocumentCount++;
                }
            }

            deduplicatedDocuments.add(document);
        }
        if (unidentifiedDocumentCount > 0) {
            log.warn("Dedup kept {} documents with neither hash nor URL metadata", unidentifiedDocumentCount);
        }
        return List.copyOf(deduplicatedDocuments);
    }

    private static String stringMetadataValue(Map<String, ?> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object rawMetadataEntry = metadata.get(key);
        return rawMetadataEntry == null ? "" : String.valueOf(rawMetadataEntry);
    }

    private Document truncateDocumentToTokenLimit(Document sourceDocument, int maxTokens) {
        String documentText = sourceDocument.getText();
        if (documentText == null || documentText.isEmpty()) {
            return sourceDocument;
        }
        int maxChars = Math.max(1, maxTokens) * ModelConfiguration.ESTIMATED_CHARS_PER_TOKEN;
        if (documentText.length() <= maxChars) {
            return sourceDocument;
        }
        String truncated = documentText.substring(0, maxChars);
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);
        if (breakPoint > maxChars * TRUNCATION_BREAK_THRESHOLD) {
            truncated = truncated.substring(0, breakPoint + 1);
        }
        truncated += "\n[...content truncated for token limits...]";
        Map<String, ?> truncationMetadata = Map.of("truncated", true, "originalLength", documentText.length());
        return documentFactory.createWithPreservedMetadata(truncated, sourceDocument.getMetadata(), truncationMetadata);
    }

    /**
     * Outcome of converting documents into citations, surfacing any partial conversion failures.
     *
     * <p>Callers must inspect {@code failedConversionCount} or call {@link #citationsOrThrow()} to
     * avoid silently receiving an incomplete citation list. A zero count means all documents
     * converted successfully.</p>
     *
     * @param citations successfully converted citations
     * @param failedConversionCount number of documents that failed citation conversion
     */
    public record CitationOutcome(List<Citation> citations, int failedConversionCount) {
        public CitationOutcome {
            citations = citations == null ? List.of() : List.copyOf(citations);
            if (failedConversionCount < 0) {
                throw new IllegalArgumentException("failedConversionCount cannot be negative");
            }
        }

        /**
         * Returns fully converted citations or rejects the incomplete conversion outcome.
         *
         * <p>Static citation responses cannot represent a source list truthfully when any source
         * document failed conversion, so callers must surface the typed failure instead of returning
         * only the successfully converted subset.</p>
         *
         * @return immutable citations when every source document converted successfully
         * @throws CitationConversionFailureException when one or more source documents failed conversion
         */
        public List<Citation> citationsOrThrow() {
            if (failedConversionCount > 0) {
                throw new CitationConversionFailureException(failedConversionCount);
            }
            return citations;
        }
    }

    /**
     * Builds citations from retrieved documents by normalizing source URLs and trimming snippets for UI display.
     *
     * <p>Returns a {@link CitationOutcome} that includes both the successfully converted citations and
     * a count of conversion failures, ensuring callers are aware of any partial failures.</p>
     */
    public CitationOutcome toCitations(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new CitationOutcome(List.of(), 0);
        }
        List<Citation> citations = new ArrayList<>();
        Set<String> retainedCitationIdentities = new HashSet<>();
        int failedConversionCount = 0;
        for (Document sourceDocument : documents) {
            if (sourceDocument == null) {
                continue;
            }
            try {
                Map<String, ?> sourceDocMetadata = sourceDocument.getMetadata();
                String rawUrl = stringMetadataValue(sourceDocMetadata, METADATA_URL);
                String title = stringMetadataValue(sourceDocMetadata, METADATA_TITLE);
                String packageName = stringMetadataValue(sourceDocMetadata, METADATA_PACKAGE);
                String documentType = stringMetadataValue(sourceDocMetadata, METADATA_DOC_TYPE);
                String refinedCitationUrl =
                        refineCitationUrl(rawUrl, sourceDocument.getText(), packageName, documentType);
                String citationIdentity = citationIdentityFor(rawUrl, refinedCitationUrl);
                if (!citationIdentity.isBlank() && !retainedCitationIdentities.add(citationIdentity)) {
                    continue;
                }
                citations.add(new Citation(
                        fragmentlessCitationSourceUrl(refinedCitationUrl),
                        title,
                        citationAnchor(refinedCitationUrl),
                        trimmedCitationSnippet(sourceDocument.getText())));
            } catch (RuntimeException citationConversionFailure) {
                failedConversionCount++;
                log.warn(
                        "Citation conversion failed (exceptionType={}, docUrl={}, docTitle={})",
                        citationConversionFailure.getClass().getSimpleName(),
                        safeMetadataValueForLogging(sourceDocument.getMetadata(), METADATA_URL),
                        safeMetadataValueForLogging(sourceDocument.getMetadata(), METADATA_TITLE),
                        citationConversionFailure);
            }
        }
        if (failedConversionCount > 0) {
            log.warn(
                    "Citation conversion completed with {} failure(s) out of {} documents",
                    failedConversionCount,
                    documents.size());
        }
        return new CitationOutcome(citations, failedConversionCount);
    }

    private static String fragmentlessCitationSourceUrl(String citationUrl) {
        int fragmentDelimiterIndex = citationUrl.indexOf(URL_FRAGMENT_DELIMITER);
        return fragmentDelimiterIndex < 0 ? citationUrl : citationUrl.substring(0, fragmentDelimiterIndex);
    }

    private static String citationAnchor(String citationUrl) {
        int fragmentDelimiterIndex = citationUrl.indexOf(URL_FRAGMENT_DELIMITER);
        return fragmentDelimiterIndex < 0 ? "" : citationUrl.substring(fragmentDelimiterIndex + 1);
    }

    /**
     * Preserves final anchors while retaining opaque identities for unresolved local sources.
     *
     * <p>Unresolved local paths share a redacted display URL, so their fragmentless raw paths remain
     * distinct. All resolvable sources use their final citation URLs so member and page anchors identify
     * separate citations.</p>
     */
    private static String citationIdentityFor(String rawUrl, String citationUrl) {
        String trimmedRawUrl = rawUrl.trim();
        if (trimmedRawUrl.startsWith(FILE_URL_PREFIX)
                && DocsSourceRegistry.resolveLocalPath(trimmedRawUrl.substring(FILE_URL_PREFIX.length()))
                        .isEmpty()) {
            return fragmentlessCitationSourceUrl(trimmedRawUrl);
        }
        return citationUrl;
    }

    /**
     * Refines a raw document URL and gates Javadoc member anchors to {@code api-docs} metadata.
     *
     */
    private String refineCitationUrl(String rawUrl, String documentText, String packageName, String documentType) {
        String normalizedUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
        String citationUrl = normalizedUrl;
        if (DOCUMENT_TYPE_API_DOCS.equals(documentType)) {
            String nestedTypeRefinedUrl = com.williamcallahan.javachat.util.JavadocLinkResolver.refineNestedTypeUrl(
                    citationUrl, documentText);
            citationUrl = com.williamcallahan.javachat.util.JavadocLinkResolver.refineMemberAnchorUrl(
                    nestedTypeRefinedUrl, documentText, packageName);
        }
        if (citationUrl.startsWith("http://") || citationUrl.startsWith("https://")) {
            return DocsSourceRegistry.canonicalizeHttpDocUrl(citationUrl);
        }
        return citationUrl;
    }

    private String trimmedCitationSnippet(String sourceText) {
        String snippetText = Optional.ofNullable(sourceText).orElse("");
        if (snippetText.length() <= CITATION_SNIPPET_MAX_LENGTH) {
            return snippetText;
        }
        return snippetText.substring(0, CITATION_SNIPPET_MAX_LENGTH) + "…";
    }

    private String safeMetadataValueForLogging(Map<String, ?> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object metadataValue = metadata.get(key);
        if (metadataValue == null) {
            return "";
        }
        try {
            String metadataText = String.valueOf(metadataValue);
            return METADATA_URL.equals(key) ? DocsSourceRegistry.normalizeDocUrl(metadataText) : metadataText;
        } catch (RuntimeException _) {
            return "[unprintable:" + metadataValue.getClass().getSimpleName() + "]";
        }
    }
}
