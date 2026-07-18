package com.williamcallahan.javachat.service;

import reactor.core.publisher.Flux;

/**
 * Result of a streaming response and its selected provider.
 *
 * @param textChunks the streaming response flux
 * @param provider the LLM provider selected for this request
 */
public record StreamingResult(Flux<String> textChunks, RateLimitService.ApiProvider provider) {
    /** Returns a user-friendly display name for the provider. */
    public String providerDisplayName() {
        return provider.getName();
    }
}
