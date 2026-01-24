package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents the response variants for standard markdown rendering endpoints.
 */
public sealed interface MarkdownRenderResponse
    permits MarkdownRenderOutcome, MarkdownErrorResponse {
}
