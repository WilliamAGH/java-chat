package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import java.util.Objects;

/**
 * Couples an OpenAI-compatible client with the provider metadata used for routing decisions.
 *
 * @param client client instance used to execute requests
 * @param provider provider identity used for rate-limit and fallback decisions
 */
record OpenAiProviderCandidate(OpenAIClient client, RateLimitService.ApiProvider provider) {
    OpenAiProviderCandidate {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(provider, "provider");
    }
}
