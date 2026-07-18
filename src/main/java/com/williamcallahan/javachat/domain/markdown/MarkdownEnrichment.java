package com.williamcallahan.javachat.domain.markdown;

import com.williamcallahan.javachat.domain.text.UnicodeVisibleContent;

/**
 * Represents one enrichment found in Markdown.
 */
public record MarkdownEnrichment(String type, String content, int position) {

    /** Enforces the shared enrichment text and source-position invariants. */
    public MarkdownEnrichment {
        if (!UnicodeVisibleContent.hasVisibleContent(type)) {
            throw new IllegalArgumentException("Enrichment type cannot be null or blank");
        }
        if (isBlankEnrichmentText(content)) {
            throw new IllegalArgumentException("Enrichment content cannot be null or blank");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Enrichment position must be non-negative");
        }
    }

    /** Reports whether enrichment text would render without a visible character. */
    public static boolean isBlankEnrichmentText(String enrichmentText) {
        return !UnicodeVisibleContent.hasVisibleContent(enrichmentText);
    }
}
