package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents a reminder enrichment element.
 * Highlights important points to remember.
 */
public record Reminder(String content, EnrichmentPriority priority, int position) implements MarkdownEnrichment {
    
    public Reminder {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Reminder content cannot be null or empty");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Reminder priority cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Reminder position must be non-negative");
        }
    }
    
    /**
     * Creates a reminder with high priority.
     * @param content the reminder content
     * @param position position in document
     * @return new Reminder instance
     */
    public static Reminder create(String content, int position) {
        return new Reminder(content, EnrichmentPriority.HIGH, position);
    }
    
    @Override
    public String type() {
        return "reminder";
    }
}
