package com.williamcallahan.javachat.domain.markdown;

import java.util.Objects;

/**
 * Describes a markdown rendering failure.
 */
public record MarkdownErrorResponse(String error, String details)
    implements MarkdownRenderResponse, MarkdownCacheStatsResponse, MarkdownCacheClearResponse {
    public MarkdownErrorResponse {
        Objects.requireNonNull(error, "Error message cannot be null");
        details = details == null ? "" : details;
    }
}
