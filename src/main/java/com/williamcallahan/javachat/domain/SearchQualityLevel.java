package com.williamcallahan.javachat.domain;

import org.springframework.ai.document.Document;

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
    NONE("No relevant documents found. Using general knowledge only."),

    /**
     * Documents came from keyword/fallback search rather than semantic embeddings.
     */
    KEYWORD_SEARCH("Found %d documents via keyword search (embedding service unavailable). Results may be less semantically relevant."),

    /**
     * All retrieved documents are high-quality semantic matches.
     */
    HIGH_QUALITY("Found %d high-quality relevant documents via semantic search."),

    /**
     * Mix of high and lower quality results from semantic search.
     */
    MIXED_QUALITY("Found %d documents (%d high-quality) via search. Some results may be less relevant.");

    /**
     * Minimum content length to consider a document as having substantial content.
     * Documents shorter than this threshold are classified as lower quality.
     */
    private static final int SUBSTANTIAL_CONTENT_THRESHOLD = 100;

    private final String messageTemplate;

    SearchQualityLevel(String messageTemplate) {
        this.messageTemplate = messageTemplate;
    }

    /**
     * Formats the quality message with document counts.
     *
     * @param totalCount total number of documents
     * @param highQualityCount number of high-quality documents
     * @return formatted message for the LLM
     */
    public String formatMessage(int totalCount, int highQualityCount) {
        return switch (this) {
            case NONE -> messageTemplate;
            case KEYWORD_SEARCH, HIGH_QUALITY -> String.format(messageTemplate, totalCount);
            case MIXED_QUALITY -> String.format(messageTemplate, totalCount, highQualityCount);
        };
    }

    /**
     * Counts documents with substantial content (length exceeds threshold).
     *
     * @param docs the documents to evaluate
     * @return count of high-quality documents with substantial content
     */
    private static long countHighQuality(List<Document> docs) {
        if (docs == null) {
            return 0;
        }
        return docs.stream()
                .filter(doc -> {
                    String content = doc.getText();
                    return content != null && content.length() > SUBSTANTIAL_CONTENT_THRESHOLD;
                })
                .count();
    }

    /**
     * Determines the search quality level for a set of retrieved documents.
     *
     * @param docs the retrieved documents
     * @return the appropriate quality level
     */
    public static SearchQualityLevel determine(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return NONE;
        }

        // Check if documents came from keyword/fallback search
        boolean likelyKeywordSearch = docs.stream()
                .anyMatch(doc -> {
                    String url = String.valueOf(doc.getMetadata().getOrDefault("url", ""));
                    return url.contains("local-search") || url.contains("keyword");
                });

        if (likelyKeywordSearch) {
            return KEYWORD_SEARCH;
        }

        // Count high-quality documents (has substantial content)
        long highQualityCount = countHighQuality(docs);

        if (highQualityCount == docs.size()) {
            return HIGH_QUALITY;
        }

        return MIXED_QUALITY;
    }

    /**
     * Generates the complete search quality note for the documents.
     *
     * @param docs the retrieved documents
     * @return formatted quality message
     */
    public static String describeQuality(List<Document> docs) {
        SearchQualityLevel level = determine(docs);
        int totalCount = docs != null ? docs.size() : 0;
        long highQualityCount = countHighQuality(docs);

        return level.formatMessage(totalCount, (int) highQualityCount);
    }
}
