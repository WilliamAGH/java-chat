package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import com.williamcallahan.javachat.util.QueryVersionExtractor.VersionFilterPatterns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private static final int DIAGNOSTIC_PREVIEW_LENGTH = 500;
    private static final int CITATION_SNIPPET_MAX_LENGTH = 500;

    private static final String METADATA_URL = "url";
    private static final String METADATA_TITLE = "title";
    private static final String METADATA_PACKAGE = "package";
    private static final String METADATA_HASH = "hash";

    private final HybridSearchService hybridSearchService;
    private final AppProperties props;
    private final RerankerService rerankerService;
    private final DocumentFactory documentFactory;

    /**
     * Creates a retrieval service backed by gRPC hybrid search with RRF fusion and a reranker.
     *
     * @param hybridSearchService gRPC-based hybrid search across all collections
     * @param props application configuration
     * @param rerankerService reranker for result ordering
     * @param documentFactory document factory for metadata preservation
     */
    public RetrievalService(
            HybridSearchService hybridSearchService,
            AppProperties props,
            RerankerService rerankerService,
            DocumentFactory documentFactory) {
        this.hybridSearchService = hybridSearchService;
        this.props = props;
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
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);

        int baseTopK = Math.max(1, props.getRag().getSearchTopK());

        List<Document> candidates = hybridSearchService.search(boostedQuery, baseTopK);

        List<Document> filtered = applyVersionFilterIfPresent(versionFilter, candidates);
        List<Document> uniqueByHash = dedupeByHashThenUrl(filtered);

        List<Document> reranked =
                rerankerService.rerank(query, uniqueByHash, props.getRag().getSearchReturnK());

        if (!reranked.isEmpty()) {
            Map<String, ?> metadata = reranked.get(0).getMetadata();
            int metadataSize = metadata.size();
            String docText = Optional.ofNullable(reranked.get(0).getText()).orElse("");
            int previewLength = Math.min(DEBUG_FIRST_DOC_PREVIEW_LENGTH, docText.length());
            log.info("First doc metadata size: {}", metadataSize);
            log.info("First doc content preview length: {}", previewLength);
        }
        return new RetrievalOutcome(reranked, List.of());
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     */
    public RetrievalOutcome retrieveWithLimitOutcome(String query, int maxDocs, int maxTokensPerDoc) {
        RetrievalOutcome outcome = retrieveOutcome(query);
        List<Document> docs = outcome.documents();
        if (docs.isEmpty()) {
            return outcome;
        }
        List<Document> truncatedDocs = docs.stream()
                .limit(Math.max(1, maxDocs))
                .map(doc -> truncateDocumentToTokenLimit(doc, maxTokensPerDoc))
                .collect(Collectors.toList());
        return new RetrievalOutcome(truncatedDocs, outcome.notices());
    }

    public List<Document> retrieveWithLimit(String query, int maxDocs, int maxTokensPerDoc) {
        return retrieveWithLimitOutcome(query, maxDocs, maxTokensPerDoc).documents();
    }

    private List<Document> applyVersionFilterIfPresent(
            Optional<VersionFilterPatterns> versionFilter, List<Document> docs) {
        if (versionFilter.isEmpty()) {
            return docs;
        }
        VersionFilterPatterns filter = versionFilter.get();
        List<Document> matched = docs.stream()
                .filter(doc -> filter.matchesMetadata(
                        stringMetadataValue(doc.getMetadata(), METADATA_URL),
                        stringMetadataValue(doc.getMetadata(), METADATA_TITLE)))
                .collect(Collectors.toList());
        return matched.isEmpty() ? docs : matched;
    }

    private List<Document> dedupeByHashThenUrl(List<Document> docs) {
        if (docs.isEmpty()) {
            return docs;
        }
        Map<String, Document> byHash = new LinkedHashMap<>();
        List<Document> withoutHash = new ArrayList<>();
        for (Document doc : docs) {
            String hash = stringMetadataValue(doc.getMetadata(), METADATA_HASH);
            if (!hash.isBlank()) {
                byHash.putIfAbsent(hash, doc);
            } else {
                withoutHash.add(doc);
            }
        }
        Map<String, Document> byUrl = new LinkedHashMap<>();
        List<Document> unidentified = new ArrayList<>();
        for (Document doc : withoutHash) {
            String url = stringMetadataValue(doc.getMetadata(), METADATA_URL);
            if (!url.isBlank()) {
                byUrl.putIfAbsent(url, doc);
            } else {
                unidentified.add(doc);
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
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Document truncateDocumentToTokenLimit(Document doc, int maxTokens) {
        String content = doc.getText();
        if (content == null || content.isEmpty()) {
            return doc;
        }
        int maxChars = Math.max(1, maxTokens) * 4;
        if (content.length() <= maxChars) {
            return doc;
        }
        String truncated = content.substring(0, maxChars);
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);
        if (breakPoint > maxChars * 0.8) {
            truncated = truncated.substring(0, breakPoint + 1);
        }
        truncated += "\n[...content truncated for token limits...]";
        Map<String, ?> truncationMetadata = Map.of("truncated", true, "originalLength", content.length());
        return documentFactory.createWithPreservedMetadata(truncated, doc.getMetadata(), truncationMetadata);
    }

    /**
     * Builds citations from retrieved documents by normalizing source URLs and trimming snippets for UI display.
     */
    public List<Citation> toCitations(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Citation> citations = new ArrayList<>();
        for (Document sourceDoc : docs) {
            if (sourceDoc == null) {
                continue;
            }
            Map<String, ?> metadata = sourceDoc.getMetadata();
            String rawUrl = stringMetadataValue(metadata, METADATA_URL);
            String title = stringMetadataValue(metadata, METADATA_TITLE);
            String url = DocsSourceRegistry.normalizeDocUrl(rawUrl);
            url = com.williamcallahan.javachat.util.JavadocLinkResolver.refineNestedTypeUrl(url, sourceDoc.getText());
            String pkg = stringMetadataValue(metadata, METADATA_PACKAGE);
            url = com.williamcallahan.javachat.util.JavadocLinkResolver.refineMemberAnchorUrl(
                    url, sourceDoc.getText(), pkg);
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url = DocsSourceRegistry.canonicalizeHttpDocUrl(url);
            }
            String snippet = Optional.ofNullable(sourceDoc.getText()).orElse("");
            citations.add(new Citation(
                    url,
                    title,
                    "",
                    snippet.length() > CITATION_SNIPPET_MAX_LENGTH
                            ? snippet.substring(0, CITATION_SNIPPET_MAX_LENGTH) + "…"
                            : snippet));
        }
        return citations;
    }
}
