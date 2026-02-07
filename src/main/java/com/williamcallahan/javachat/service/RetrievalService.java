package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import com.williamcallahan.javachat.util.QueryVersionExtractor.VersionFilterPatterns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Retrieves documents and diagnostic notices for a query.
     */
    public RetrievalOutcome retrieveOutcome(String query) {
        if (query == null || query.isBlank()) {
            return new RetrievalOutcome(List.of(), List.of());
        }
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);
        RetrievalConstraint retrievalConstraint = toRetrievalConstraint(versionFilter);
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);

        int baseTopK = Math.max(1, appProperties.getRag().getSearchTopK());

        HybridSearchService.SearchOutcome searchOutcome =
                hybridSearchService.searchOutcome(boostedQuery, baseTopK, retrievalConstraint);
        List<Document> candidates = searchOutcome.documents();

        List<Document> filtered = retrievalConstraint.hasServerSideConstraint()
                ? candidates
                : applyVersionFilterIfPresent(versionFilter, candidates);
        List<Document> uniqueByHash = dedupeByHashThenUrl(filtered);

        List<Document> reranked = rerankerService.rerank(
                query, uniqueByHash, appProperties.getRag().getSearchReturnK());

        if (!reranked.isEmpty()) {
            Map<String, ?> firstDocMetadata = reranked.get(0).getMetadata();
            int metadataSize = firstDocMetadata.size();
            String firstDocumentText =
                    Optional.ofNullable(reranked.get(0).getText()).orElse("");
            int previewLength = Math.min(DEBUG_FIRST_DOC_PREVIEW_LENGTH, firstDocumentText.length());
            log.debug("First doc metadata size: {}", metadataSize);
            log.debug("First doc content preview length: {}", previewLength);
        }
        List<RetrievalNotice> retrievalNotices = searchOutcome.notices().stream()
                .map(searchNotice -> new RetrievalNotice(searchNotice.summary(), searchNotice.details()))
                .toList();
        return new RetrievalOutcome(reranked, retrievalNotices);
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     */
    public RetrievalOutcome retrieveWithLimitOutcome(String query, int maxDocs, int maxTokensPerDoc) {
        RetrievalOutcome outcome = retrieveOutcome(query);
        List<Document> documents = outcome.documents();
        if (documents.isEmpty()) {
            return outcome;
        }
        List<Document> truncatedDocs = documents.stream()
                .limit(Math.max(1, maxDocs))
                .map(document -> truncateDocumentToTokenLimit(document, maxTokensPerDoc))
                .toList();
        return new RetrievalOutcome(truncatedDocs, outcome.notices());
    }

    /**
     * Retrieves documents while capping document count and per-document token budget.
     */
    public List<Document> retrieveWithLimit(String query, int maxDocs, int maxTokensPerDoc) {
        return retrieveWithLimitOutcome(query, maxDocs, maxTokensPerDoc).documents();
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

    private List<Document> dedupeByHashThenUrl(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Map<String, Document> byHash = new LinkedHashMap<>();
        List<Document> withoutHash = new ArrayList<>();
        for (Document document : documents) {
            String hash = stringMetadataValue(document.getMetadata(), METADATA_HASH);
            if (!hash.isBlank()) {
                byHash.putIfAbsent(hash, document);
            } else {
                withoutHash.add(document);
            }
        }
        Map<String, Document> byUrl = new LinkedHashMap<>();
        List<Document> unidentified = new ArrayList<>();
        for (Document document : withoutHash) {
            String url = stringMetadataValue(document.getMetadata(), METADATA_URL);
            if (!url.isBlank()) {
                byUrl.putIfAbsent(url, document);
            } else {
                unidentified.add(document);
            }
        }
        if (!unidentified.isEmpty()) {
            log.warn("Dedup kept {} documents with neither hash nor URL metadata", unidentified.size());
        }
        List<Document> combined = new ArrayList<>(byHash.values());
        combined.addAll(byUrl.values());
        combined.addAll(unidentified);
        return List.copyOf(combined);
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
     * <p>Callers must inspect {@code failedConversionCount} to detect partial failures rather than
     * silently receiving an incomplete citation list. A zero count means all documents converted
     * successfully.</p>
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
                String url = refineCitationUrl(rawUrl, sourceDocument.getText(), packageName);
                citations.add(new Citation(url, title, "", trimmedCitationSnippet(sourceDocument.getText())));
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

    /**
     * Refines a raw document URL through nested-type and member-anchor resolution, then canonicalizes.
     *
     */
    private String refineCitationUrl(String rawUrl, String docText, String packageName) {
        String normalizedUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
        String nestedTypeRefinedUrl =
                com.williamcallahan.javachat.util.JavadocLinkResolver.refineNestedTypeUrl(normalizedUrl, docText);
        String memberAnchorRefinedUrl = com.williamcallahan.javachat.util.JavadocLinkResolver.refineMemberAnchorUrl(
                nestedTypeRefinedUrl, docText, packageName);
        if (memberAnchorRefinedUrl.startsWith("http://") || memberAnchorRefinedUrl.startsWith("https://")) {
            return DocsSourceRegistry.canonicalizeHttpDocUrl(memberAnchorRefinedUrl);
        }
        return memberAnchorRefinedUrl;
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
            return String.valueOf(metadataValue);
        } catch (RuntimeException _) {
            return "[unprintable:" + metadataValue.getClass().getSimpleName() + "]";
        }
    }
}
