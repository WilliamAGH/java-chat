package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents the response variants for markdown cache statistics requests.
 */
public sealed interface MarkdownCacheStatsResponse permits MarkdownCacheStatsSnapshot, MarkdownErrorResponse {}
