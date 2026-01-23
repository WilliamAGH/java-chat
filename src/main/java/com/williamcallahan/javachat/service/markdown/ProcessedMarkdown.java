package com.williamcallahan.javachat.service.markdown;

import java.util.List;
import java.util.Objects;

/**
 * Represents the result of markdown processing with structured data.
 * This replaces string-based processing with typed objects for better maintainability.
 * 
 * @param html The rendered HTML content
 * @param citations List of extracted citations with metadata
 * @param enrichments List of structured enrichment objects
 * @param warnings List of non-fatal processing warnings
 * @param processingTimeMs Time taken to process the markdown
 */
public record ProcessedMarkdown(
    String html,
    List<MarkdownCitation> citations,
    List<MarkdownEnrichment> enrichments,
    List<ProcessingWarning> warnings,
    long processingTimeMs
) {
    
    public ProcessedMarkdown {
        Objects.requireNonNull(html, "HTML content cannot be null");
        Objects.requireNonNull(citations, "Citations list cannot be null");
        Objects.requireNonNull(enrichments, "Enrichments list cannot be null");
        Objects.requireNonNull(warnings, "Warnings list cannot be null");
    }
    
    /**
     * Checks if processing completed without warnings.
     * @return true if no warnings were generated during processing
     */
    public boolean isClean() {
        return warnings.isEmpty();
    }
    
    /**
     * Gets the total number of structured elements (citations + enrichments).
     * @return count of structured elements
     */
    public int getStructuredElementCount() {
        return citations.size() + enrichments.size();
    }
}
