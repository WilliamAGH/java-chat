package com.williamcallahan.javachat.domain.markdown;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a structured citation extracted from markdown content.
 * This replaces string-based citation processing with typed objects.
 * 
 * Note: Named MarkdownCitation to avoid conflict with existing model.Citation class.
 */
public record MarkdownCitation(
    String url,
    String title,
    String snippet,
    CitationType type,
    int position,
    LocalDateTime extractedAt
) {
    
    public MarkdownCitation {
        Objects.requireNonNull(url, "Citation URL cannot be null");
        Objects.requireNonNull(title, "Citation title cannot be null");
        Objects.requireNonNull(type, "Citation type cannot be null");
        if (position < 0) {
            throw new IllegalArgumentException("Citation position must be non-negative");
        }
    }
    
    /**
     * Creates a citation with current timestamp.
     * @param url The citation URL
     * @param title The citation title
     * @param snippet Optional snippet text (can be null)
     * @param type The citation type
     * @param position Position in the document
     * @return new MarkdownCitation instance
     */
    public static MarkdownCitation create(String url, String title, String snippet, CitationType type, int position) {
        return new MarkdownCitation(url, title, snippet != null ? snippet : "", type, position, LocalDateTime.now());
    }
    
    /**
     * Checks if this citation has a snippet.
     * @return true if snippet is not empty
     */
    public boolean hasSnippet() {
        return snippet != null && !snippet.trim().isEmpty();
    }
    
    /**
     * Gets the domain from the URL for display purposes.
     * @return domain string or "unknown" if URL is invalid
     */
    public String getDomain() {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception urlParseException) {
            return "unknown";
        }
    }
}
