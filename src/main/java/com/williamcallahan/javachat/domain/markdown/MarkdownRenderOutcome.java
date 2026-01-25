package com.williamcallahan.javachat.domain.markdown;

import java.util.Objects;

/**
 * Describes the outcome of rendering markdown to HTML for standard endpoints.
 */
public record MarkdownRenderOutcome(String html, String source, boolean cached, int citations, int enrichments)
        implements MarkdownRenderResponse {
    public MarkdownRenderOutcome {
        Objects.requireNonNull(html, "Rendered HTML cannot be null");
        Objects.requireNonNull(source, "Render source cannot be null");
        if (citations < 0) {
            throw new IllegalArgumentException("Citation count must be non-negative");
        }
        if (enrichments < 0) {
            throw new IllegalArgumentException("Enrichment count must be non-negative");
        }
    }
}
