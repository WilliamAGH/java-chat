package com.williamcallahan.javachat.domain.markdown;

/**
 * Base interface for structured enrichment elements.
 * This replaces regex-based enrichment processing with type-safe objects.
 * 
 * Note: Named MarkdownEnrichment to avoid conflict with existing model.Enrichment class.
 */
public sealed interface MarkdownEnrichment 
    permits Hint, Warning, Background, Example, Reminder {
    
    /**
     * Gets the enrichment type identifier.
     * @return type string
     */
    String type();
    
    /**
     * Gets the enrichment content.
     * @return content string
     */
    String content();
    
    /**
     * Gets the enrichment priority for rendering order.
     * @return priority level
     */
    EnrichmentPriority priority();
    
    /**
     * Gets the position in the document where this enrichment was found.
     * @return document position
     */
    int position();
    
    /**
     * Checks if this enrichment has non-empty content.
     * @return true if content is not empty
     */
    default boolean hasContent() {
        return content() != null && !content().trim().isEmpty();
    }
}
