package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.support.RetrievalErrorClassifier;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import com.williamcallahan.javachat.util.QueryVersionExtractor.VersionFilterPatterns;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
// TODO: Add DJL-based BGE reranker or LLM rerank; embedding-based MMR removed for now
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Retrieves and reranks context documents for RAG queries and converts them into citation-ready metadata.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(
        RetrievalService.class
    );

    /** Max chars for first document preview in debug logs */
    private static final int DEBUG_FIRST_DOC_PREVIEW_LENGTH = 200;

    /** Max chars for diagnostic log previews of top reranked documents */
    private static final int DIAGNOSTIC_PREVIEW_LENGTH = 500;

    /** Max chars for citation snippets shown to users */
    private static final int CITATION_SNIPPET_MAX_LENGTH = 500;

    private static final String METADATA_URL = "url";
    private static final String METADATA_TITLE = "title";
    private static final String METADATA_PACKAGE = "package";
    private static final String METADATA_RETRIEVAL_SOURCE = "retrievalSource";
    private static final String METADATA_FALLBACK_REASON = "fallbackReason";
    private static final String SOURCE_KEYWORD_FALLBACK = "keyword_fallback";

    private final VectorStore vectorStore;
    private final AppProperties props;
    private final RerankerService rerankerService;
    private final LocalSearchService localSearch;
    private final DocumentFactory documentFactory;

    /**
     * Creates a retrieval service backed by a vector store, reranker, and local fallback search.
     *
     * @param vectorStore vector similarity store
     * @param props application configuration
     * @param rerankerService reranker for result ordering
     * @param localSearch local keyword fallback search
     * @param documentFactory document factory for metadata preservation
     */
    public RetrievalService(
        VectorStore vectorStore,
        AppProperties props,
        RerankerService rerankerService,
        LocalSearchService localSearch,
        DocumentFactory documentFactory
    ) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.rerankerService = rerankerService;
        this.localSearch = localSearch;
        this.documentFactory = documentFactory;
    }

    /**
     * Retrieves documents for a query using vector search and reranking, falling back to local search on failure.
     */
    public List<Document> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // Extract version filter patterns if query mentions a specific Java version
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);

        // Boost query with version context for better semantic matching
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);

        // Initial vector search
        List<Document> docs;
        try {
            int baseTopK = Math.max(1, props.getRag().getSearchTopK());
            docs = executeVersionAwareSearch(query, boostedQuery, versionFilter, baseTopK);
        } catch (RuntimeException exception) {
            return handleVectorSearchFailure(exception, query);
        }

        // MMR re-ranking using embeddings
        List<Document> uniqueByUrl = docs
            .stream()
            .collect(
                Collectors.toMap(
                    doc -> String.valueOf(doc.getMetadata().get(METADATA_URL)),
                    doc -> doc,
                    (first, dup) -> first
                )
            )
            .values()
            .stream()
            .collect(Collectors.toList());

        List<Document> reranked = rerankerService.rerank(
            query,
            uniqueByUrl,
            props.getRag().getSearchReturnK()
        );
        // DIAGNOSTIC: Log top reranked doc preview (truncated)
        if (!reranked.isEmpty()) {
            String topText = Optional.ofNullable(reranked.get(0).getText()).orElse("");
            int previewLength = Math.min(DIAGNOSTIC_PREVIEW_LENGTH, topText.length());
            log.info("[DIAG] RAG top doc (post-rerank) previewLength={}", previewLength);
        }
        return reranked;
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     * Used for GPT-5 which has an 8K input token limit.
     */
    public List<Document> retrieveWithLimit(
        String query,
        int maxDocs,
        int maxTokensPerDoc
    ) {
        // Extract version filter patterns if query mentions a specific Java version
        Optional<VersionFilterPatterns> versionFilter = QueryVersionExtractor.extractFilterPatterns(query);

        // Boost query with version context for better semantic matching
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query);

        // Initial vector search with custom topK
        List<Document> docs;
        try {
            int baseTopK = Math.max(
                1,
                Math.max(maxDocs, props.getRag().getSearchTopK())
            );
            docs = executeVersionAwareSearch(query, boostedQuery, versionFilter, baseTopK);
        } catch (RuntimeException exception) {
            docs = executeFallbackSearch(query, maxDocs, exception);
        }

        // Early return if no documents to process (fallback also failed or no matches)
        if (docs.isEmpty()) {
            log.info("No documents to process after retrieval/fallback - returning empty list");
            return List.of();
        }

        // Truncate documents to token limits and return limited count
        List<Document> truncatedDocs = docs
            .stream()
            .limit(maxDocs)
            .map(doc -> truncateDocumentToTokenLimit(doc, maxTokensPerDoc))
            .collect(Collectors.toList());

        // Apply reranking with limited return count
        List<Document> uniqueByUrl = truncatedDocs
            .stream()
            .collect(
                Collectors.toMap(
                    doc -> String.valueOf(doc.getMetadata().get(METADATA_URL)),
                    doc -> doc,
                    (first, dup) -> first
                )
            )
            .values()
            .stream()
            .collect(Collectors.toList());

        return rerankerService.rerank(query, uniqueByUrl, maxDocs);
    }

    private List<Document> executeVersionAwareSearch(
        String query,
        String boostedQuery,
        Optional<VersionFilterPatterns> versionFilter,
        int baseTopK
    ) {
        // Fetch more candidates when version filtering is active
        int topK = versionFilter.isPresent() ? baseTopK * 2 : baseTopK;

        if (versionFilter.isPresent()) {
            log.info("Version filter detected; using boosted query and expanded topK");
        }
        log.info("TopK requested: {}", topK);

        SearchRequest searchRequest = SearchRequest.builder()
            .query(boostedQuery)
            .topK(topK)
            .build();

        List<Document> docs = vectorStore.similaritySearch(searchRequest);
        log.info("VectorStore returned {} documents", docs.size());

        // Apply version-based post-filtering if version was detected
        if (versionFilter.isPresent()) {
            VersionFilterPatterns filter = versionFilter.get();
            List<Document> versionMatchedDocs = docs.stream()
                .filter(doc -> filter.matchesUrl(String.valueOf(doc.getMetadata().get(METADATA_URL))))
                .collect(Collectors.toList());

            log.info("Version filter matched {} of {} documents",
                versionMatchedDocs.size(), docs.size());

            // Use version-matched docs if we have enough, otherwise fall back to all docs
            if (versionMatchedDocs.size() >= 2) {
                docs = versionMatchedDocs;
            } else {
                log.info("Insufficient version-specific docs ({}), using all {} candidates",
                    versionMatchedDocs.size(), docs.size());
            }
        }

        if (!docs.isEmpty()) {
            Map<String, Object> metadata = docs.get(0).getMetadata();
            int metadataSize = metadata.size();
            String docText = Optional.ofNullable(docs.get(0).getText()).orElse("");
            int previewLength = Math.min(DEBUG_FIRST_DOC_PREVIEW_LENGTH, docText.length());
            log.info("First doc metadata size: {}", metadataSize);
            log.info("First doc content preview length: {}", previewLength);
        }
        return docs;
    }

    /**
     * Truncate a document to a maximum token count.
     */
    private Document truncateDocumentToTokenLimit(Document doc, int maxTokens) {
        String content = doc.getText();
        if (content == null || content.isEmpty()) {
            return doc;
        }

        // Conservative estimation: ~4 chars per token
        int maxChars = maxTokens * 4;

        if (content.length() <= maxChars) {
            return doc;
        }

        // Truncate and add indicator
        String truncated = content.substring(0, maxChars);

        // Try to break at a sentence or paragraph boundary
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);

        if (breakPoint > maxChars * 0.8) {
            // Only break if we're not losing too much
            truncated = truncated.substring(0, breakPoint + 1);
        }

        truncated += "\n[...content truncated for token limits...]";

        // Create new document with truncated content, preserving all original metadata
        // and adding truncation-specific metadata
        Map<String, Object> truncationMetadata = Map.of(
            "truncated", true,
            "originalLength", content.length()
        );

        return documentFactory.createWithPreservedMetadata(
            truncated,
            doc.getMetadata(),
            truncationMetadata
        );
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
            Map<String, Object> metadata = Optional.ofNullable(sourceDoc.getMetadata()).orElse(Map.of());
            String rawUrl = String.valueOf(metadata.getOrDefault("url", ""));
            String title = String.valueOf(metadata.getOrDefault("title", ""));
            String url = DocsSourceRegistry.normalizeDocUrl(rawUrl);
            // Refine Javadoc URLs to nested type pages where the chunk references them
            url =
                com.williamcallahan.javachat.util.JavadocLinkResolver.refineNestedTypeUrl(
                    url,
                    sourceDoc.getText()
                );
            // Append member anchors (methods/constructors) when confidently derivable
            String pkg = String.valueOf(metadata.getOrDefault("package", ""));
            url =
                com.williamcallahan.javachat.util.JavadocLinkResolver.refineMemberAnchorUrl(
                    url,
                    sourceDoc.getText(),
                    pkg
                );
            // Final canonicalization in case of any accidental duplications
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url = DocsSourceRegistry.canonicalizeHttpDocUrl(url);
            }
            String snippet = Optional.ofNullable(sourceDoc.getText()).orElse("");

            // For book sources, we now link to public /pdfs path (handled by normalizeCitationUrl)

            citations.add(
                new Citation(
                    url,
                    title,
                    "",
                    snippet.length() > CITATION_SNIPPET_MAX_LENGTH
                        ? snippet.substring(0, CITATION_SNIPPET_MAX_LENGTH) + "…"
                        : snippet
                )
            );
        }
        return citations;
    }

    // TODO: Implement MMR and reranker integration

    /**
     * Handle vector search failure by logging context and falling back to local keyword search.
     * Documents returned include metadata indicating they came from fallback search.
     */
    private List<Document> handleVectorSearchFailure(
        RuntimeException exception,
        String query
    ) {
        String errorType = RetrievalErrorClassifier.determineErrorType(exception);
        log.warn("Vector search unavailable; falling back to local keyword search");

        RetrievalErrorClassifier.logUserFriendlyErrorContext(log, errorType, exception);

        // Use searchTopK (not searchReturnK) to match the primary path's candidate pool size
        LocalSearchService.SearchOutcome outcome = localSearch.search(query, props.getRag().getSearchTopK());

        if (outcome.isFailed()) {
            log.error("Local keyword search also failed - returning empty hits");
            return List.of();
        }

        log.info("Local keyword search returned {} hits", outcome.hits().size());

        return outcome.hits()
            .stream()
            .map(hit -> {
                Document doc = documentFactory.createLocalDocument(hit.text(), hit.url());
                // Mark document as coming from fallback search
                doc.getMetadata().put(METADATA_RETRIEVAL_SOURCE, SOURCE_KEYWORD_FALLBACK);
                doc.getMetadata().put(METADATA_FALLBACK_REASON, errorType);
                return doc;
            })
            .limit(props.getRag().getSearchReturnK())
            .collect(Collectors.toList());
    }

    private List<Document> executeFallbackSearch(String query, int maxDocs, RuntimeException exception) {
        String errorType = RetrievalErrorClassifier.determineErrorType(exception);
        log.warn("Vector search unavailable; falling back to local keyword search with limits");

        RetrievalErrorClassifier.logUserFriendlyErrorContext(log, errorType, exception);

        // Fallback to local search with limits
        LocalSearchService.SearchOutcome outcome = localSearch.search(query, maxDocs);

        if (outcome.isFailed()) {
            log.error("Local keyword search also failed - returning empty hits");
            return List.of();
        }

        log.info("Local keyword search returned {} hits", outcome.hits().size());

        return outcome.hits()
            .stream()
            .map(hit -> {
                Document doc = documentFactory.createLocalDocument(hit.text(), hit.url());
                doc.getMetadata().put(METADATA_RETRIEVAL_SOURCE, SOURCE_KEYWORD_FALLBACK);
                doc.getMetadata().put(METADATA_FALLBACK_REASON, errorType);
                return doc;
            })
            .collect(Collectors.toList());
    }

}
