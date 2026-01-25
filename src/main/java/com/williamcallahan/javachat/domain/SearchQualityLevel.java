package com.williamcallahan.javachat.domain;

import java.util.List;

/**
 * Categorizes the quality of search results for contextualizing LLM responses.
 *
 * <p>Replaces sequential boolean checks with self-describing behavior. Each level
 * knows how to describe itself to the LLM so responses can be appropriately calibrated.</p>
 */
public enum SearchQualityLevel {
    /**
     * No documents were retrieved; LLM must rely on general knowledge.
     */
    NONE,

    /**
     * Documents came from keyword/fallback search rather than semantic embeddings.
     */
    KEYWORD_SEARCH,

    /**
     * All retrieved documents are high-quality semantic matches.
     */
    HIGH_QUALITY,

    /**
     * Mix of high and lower quality results from semantic search.
     */
    MIXED_QUALITY;

    /**
     * Minimum content length to consider a document as having substantial content.
     * Documents shorter than this threshold are classified as lower quality.
     */
    private static final int SUBSTANTIAL_CONTENT_THRESHOLD = 100;

    /**
     * Formats the quality message with document counts.
     *
     * @param totalCount total number of documents
     * @param highQualityCount number of high-quality documents
     * @return formatted message for the LLM
     */
    public String formatMessage(int totalCount, int highQualityCount) {
        return switch (this) {
            case NONE -> "No relevant documents found. Using general knowledge only.";
            case KEYWORD_SEARCH ->
                "Found " + totalCount + " documents via keyword search (embedding service unavailable). "
                        + "Results may be less semantically relevant.";
            case HIGH_QUALITY -> "Found " + totalCount + " high-quality relevant documents via semantic search.";
            case MIXED_QUALITY ->
                "Found " + totalCount + " documents (" + highQualityCount + " high-quality) via search. "
                        + "Some results may be less relevant.";
        };
    }

    /**
     * Counts documents with substantial content (length exceeds threshold).
     *
     * @param contents the retrieved contents to evaluate
     * @return count of high-quality documents with substantial content
     */
    private static long countHighQuality(List<? extends RetrievedContent> contents) {
        if (contents == null) {
            return 0;
        }
        return contents.stream()
                .filter(content -> content.getText()
                        .filter(text -> text.length() > SUBSTANTIAL_CONTENT_THRESHOLD)
                        .isPresent())
                .count();
    }

    /**
     * Determines the search quality level for a set of retrieved contents.
     *
     * @param contents the retrieved contents
     * @return the appropriate quality level
     */
    public static SearchQualityLevel determine(List<? extends RetrievedContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return NONE;
        }

        // Check if documents came from keyword/fallback search
        boolean likelyKeywordSearch = contents.stream().anyMatch(content -> content.getSourceUrl()
                .filter(url -> url.contains("local-search") || url.contains("keyword"))
                .isPresent());

        if (likelyKeywordSearch) {
            return KEYWORD_SEARCH;
        }

        // Count high-quality documents (has substantial content)
        long highQualityCount = countHighQuality(contents);

        if (highQualityCount == contents.size()) {
            return HIGH_QUALITY;
        }

        return MIXED_QUALITY;
    }

    /**
     * Generates the complete search quality note for the contents.
     *
     * @param contents the retrieved contents
     * @return formatted quality message
     */
    public static String describeQuality(List<? extends RetrievedContent> contents) {
        SearchQualityLevel level = determine(contents);
        int totalCount = contents != null ? contents.size() : 0;
        long highQualityCount = countHighQuality(contents);

        return level.formatMessage(totalCount, (int) highQualityCount);
    }
}
