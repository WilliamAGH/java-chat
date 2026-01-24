package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents a background information enrichment element.
 * Provides contextual information and explanations.
 */
public record Background(String content, EnrichmentPriority priority, int position) implements MarkdownEnrichment {
    
    public Background {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Background content cannot be null or empty");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Background priority cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Background position must be non-negative");
        }
    }
    
    /**
     * Creates a background element with low priority.
     * @param content the background content
     * @param position position in document
     * @return new Background instance
     */
    public static Background create(String content, int position) {
        return new Background(content, EnrichmentPriority.LOW, position);
    }
    
    @Override
    public String type() {
        return "background";
    }
}
