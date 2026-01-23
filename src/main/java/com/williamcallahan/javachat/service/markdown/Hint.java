package com.williamcallahan.javachat.service.markdown;

/**
 * Represents a hint enrichment element.
 * Provides helpful tips and suggestions to users.
 */
public record Hint(String content, EnrichmentPriority priority, int position) implements MarkdownEnrichment {
    
    public Hint {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Hint content cannot be null or empty");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Hint priority cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Hint position must be non-negative");
        }
    }
    
    /**
     * Creates a hint with medium priority.
     * @param content the hint content
     * @param position position in document
     * @return new Hint instance
     */
    public static Hint create(String content, int position) {
        return new Hint(content, EnrichmentPriority.MEDIUM, position);
    }
    
    @Override
    public String type() {
        return "hint";
    }
}
