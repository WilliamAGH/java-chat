package com.williamcallahan.javachat.domain.markdown;

import java.util.Objects;

/**
 * Captures cache statistics for markdown rendering.
 */
public record MarkdownCacheStatsSnapshot(
    long hitCount,
    long missCount,
    long evictionCount,
    long size,
    String hitRate
) implements MarkdownCacheStatsResponse {
    public MarkdownCacheStatsSnapshot {
        Objects.requireNonNull(hitRate, "Hit rate string cannot be null");
        if (hitCount < 0 || missCount < 0 || evictionCount < 0 || size < 0) {
            throw new IllegalArgumentException("Cache stats must be non-negative");
        }
    }
}
