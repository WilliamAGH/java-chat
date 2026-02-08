package com.williamcallahan.javachat.service;

import reactor.core.publisher.Flux;

/**
 * Result of streaming response containing the content flux and the provider that handled the request.
 *
 * @param textChunks the streaming response flux
 * @param provider the initially selected LLM provider for this request
 * @param notices runtime streaming notices (for example, pre-first-token provider failover)
 */
public record StreamingResult(
        Flux<String> textChunks, RateLimitService.ApiProvider provider, Flux<StreamingNotice> notices) {
    /** Returns a user-friendly display name for the provider. */
    public String providerDisplayName() {
        return provider.getName();
    }
}
