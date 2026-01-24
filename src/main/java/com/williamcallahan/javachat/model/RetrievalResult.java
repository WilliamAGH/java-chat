package com.williamcallahan.javachat.model;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Encapsulates retrieval results with quality metadata.
 * Enables callers to distinguish between successful retrieval, degraded retrieval,
 * and failed retrieval, allowing appropriate handling and LLM context adjustment.
 */
public record RetrievalResult(
    List<Document> documents,
    RetrievalQuality quality,
    String qualityReason
) {

    public RetrievalResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    @Override
    public List<Document> documents() {
        return List.copyOf(documents);
    }

    /**
     * Indicates the quality/source of retrieval results.
     */
    public enum RetrievalQuality {
        /**
         * Full vector search with semantic embeddings succeeded.
         */
        VECTOR_SEARCH,

        /**
         * Vector search succeeded but results were filtered by version.
         */
        VECTOR_SEARCH_VERSION_FILTERED,

        /**
         * Vector search failed; fell back to local keyword search.
         * Results have limited semantic understanding.
         */
        LOCAL_KEYWORD_FALLBACK,

        /**
         * Local keyword search failed or returned no results.
         * Service is degraded.
         */
        SEARCH_FAILED
    }

    /**
     * Create a successful vector search result.
     */
    public static RetrievalResult vectorSearch(List<Document> documents) {
        return new RetrievalResult(documents, RetrievalQuality.VECTOR_SEARCH,
            "Semantic vector search completed successfully");
    }

    /**
     * Create a version-filtered vector search result.
     */
    public static RetrievalResult versionFiltered(List<Document> documents, String version) {
        return new RetrievalResult(documents, RetrievalQuality.VECTOR_SEARCH_VERSION_FILTERED,
            "Semantic vector search with Java " + version + " version filtering");
    }

    /**
     * Create a fallback keyword search result.
     */
    public static RetrievalResult keywordFallback(List<Document> documents, String failureReason) {
        return new RetrievalResult(documents, RetrievalQuality.LOCAL_KEYWORD_FALLBACK,
            "Fell back to local keyword search: " + failureReason);
    }

    /**
     * Create a failed search result.
     */
    public static RetrievalResult failed(String reason) {
        return new RetrievalResult(List.of(), RetrievalQuality.SEARCH_FAILED,
            "Search failed: " + reason);
    }

    /**
     * Returns true if retrieval used semantic vector search (not keyword fallback).
     */
    public boolean isSemanticSearch() {
        return quality == RetrievalQuality.VECTOR_SEARCH
            || quality == RetrievalQuality.VECTOR_SEARCH_VERSION_FILTERED;
    }

    /**
     * Returns true if retrieval degraded to a fallback mode.
     */
    public boolean isDegraded() {
        return quality == RetrievalQuality.LOCAL_KEYWORD_FALLBACK
            || quality == RetrievalQuality.SEARCH_FAILED;
    }

    /**
     * Returns true if version filtering was applied.
     */
    public boolean isVersionFiltered() {
        return quality == RetrievalQuality.VECTOR_SEARCH_VERSION_FILTERED;
    }

    /**
     * Get a user-friendly description of the retrieval quality for LLM context.
     */
    public String getLlmContextNote() {
        return switch (quality) {
            case VECTOR_SEARCH -> "";
            case VECTOR_SEARCH_VERSION_FILTERED ->
                "Note: Results filtered to match requested Java version.";
            case LOCAL_KEYWORD_FALLBACK ->
                "IMPORTANT: Using keyword-based fallback search with limited semantic understanding. " +
                "Results may be less relevant. " + qualityReason;
            case SEARCH_FAILED ->
                "WARNING: Document retrieval failed. Responding based on training knowledge only. " +
                qualityReason;
        };
    }
}
