package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents a warning enrichment element.
 * Highlights important cautions and potential issues.
 */
public record Warning(String content, EnrichmentPriority priority, int position) implements MarkdownEnrichment {

    public Warning {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Warning content cannot be null or empty");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Warning priority cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Warning position must be non-negative");
        }
    }

    /**
     * Creates a warning with high priority.
     * @param content the warning content
     * @param position position in document
     * @return new Warning instance
     */
    public static Warning create(String content, int position) {
        return new Warning(content, EnrichmentPriority.HIGH, position);
    }

    /**
     * Creates a critical warning with highest priority.
     * @param content the warning content
     * @param position position in document
     * @return new Warning instance with critical priority
     */
    public static Warning createCritical(String content, int position) {
        return new Warning(content, EnrichmentPriority.CRITICAL, position);
    }

    @Override
    public String type() {
        return "warning";
    }
}
