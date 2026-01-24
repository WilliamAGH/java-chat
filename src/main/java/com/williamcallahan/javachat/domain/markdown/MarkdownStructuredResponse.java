package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents the response variants for structured markdown rendering endpoints.
 */
public sealed interface MarkdownStructuredResponse
    permits MarkdownStructuredOutcome, MarkdownStructuredErrorResponse {
}
