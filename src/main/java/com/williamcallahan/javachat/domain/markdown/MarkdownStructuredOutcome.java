package com.williamcallahan.javachat.domain.markdown;

import java.util.List;
import java.util.Objects;

/**
 * Represents structured markdown rendering with typed citations and enrichments.
 */
public record MarkdownStructuredOutcome(
    String html,
    List<MarkdownCitation> citations,
    List<MarkdownEnrichment> enrichments,
    List<ProcessingWarning> warnings,
    long processingTimeMs,
    String source,
    int structuredElementCount,
    boolean isClean
) implements MarkdownStructuredResponse {
    public MarkdownStructuredOutcome {
        Objects.requireNonNull(html, "Rendered HTML cannot be null");
        Objects.requireNonNull(citations, "Citations cannot be null");
        Objects.requireNonNull(enrichments, "Enrichments cannot be null");
        Objects.requireNonNull(warnings, "Warnings cannot be null");
        Objects.requireNonNull(source, "Render source cannot be null");
        if (processingTimeMs < 0) {
            throw new IllegalArgumentException("Processing time must be non-negative");
        }
        if (structuredElementCount < 0) {
            throw new IllegalArgumentException("Structured element count must be non-negative");
        }
    }
}
