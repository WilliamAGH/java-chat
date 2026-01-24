package com.williamcallahan.javachat.domain.markdown;

import java.util.Objects;

/**
 * Represents the result of clearing the markdown render cache.
 */
public record MarkdownCacheClearOutcome(String status, String message) implements MarkdownCacheClearResponse {
    public MarkdownCacheClearOutcome {
        Objects.requireNonNull(status, "Cache clear status cannot be null");
        Objects.requireNonNull(message, "Cache clear message cannot be null");
    }
}
