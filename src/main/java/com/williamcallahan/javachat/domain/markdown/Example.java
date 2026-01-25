package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents an example enrichment element.
 * Provides code examples and demonstrations.
 */
public record Example(String content, EnrichmentPriority priority, int position) implements MarkdownEnrichment {

    public Example {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Example content cannot be null or empty");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Example priority cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Example position must be non-negative");
        }
    }

    /**
     * Creates an example with medium priority.
     * @param content the example content
     * @param position position in document
     * @return new Example instance
     */
    public static Example create(String content, int position) {
        return new Example(content, EnrichmentPriority.MEDIUM, position);
    }

    @Override
    public String type() {
        return "example";
    }
}
