package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents the response variants for markdown cache clear requests.
 */
public sealed interface MarkdownCacheClearResponse
    permits MarkdownCacheClearOutcome, MarkdownErrorResponse {
}
