package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Result of a streaming response and its selected provider.
 *
 * @param textChunks the streaming response flux
 * @param provider the LLM provider selected for this request
 * @param contextDocumentIds exact source-document identities retained after provider-specific truncation
 */
public record StreamingResult(
        Flux<String> textChunks, RateLimitService.ApiProvider provider, List<String> contextDocumentIds) {
    /**
     * Requires retained document identities and stores them as an immutable list.
     *
     * @throws NullPointerException when contextDocumentIds or any contained identity is null
     */
    public StreamingResult {
        contextDocumentIds = List.copyOf(Objects.requireNonNull(contextDocumentIds, "contextDocumentIds"));
    }

    /** Returns a user-friendly display name for the provider. */
    public String providerDisplayName() {
        return provider.getName();
    }
}
