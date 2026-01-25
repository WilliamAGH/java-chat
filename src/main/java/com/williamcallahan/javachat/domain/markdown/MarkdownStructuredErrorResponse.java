package com.williamcallahan.javachat.domain.markdown;

import java.util.Objects;

/**
 * Describes a structured markdown rendering failure with a source marker.
 */
public record MarkdownStructuredErrorResponse(String error, String source, String details)
        implements MarkdownStructuredResponse {
    public MarkdownStructuredErrorResponse {
        Objects.requireNonNull(error, "Error message cannot be null");
        Objects.requireNonNull(source, "Error source cannot be null");
        details = details == null ? "" : details;
    }
}
