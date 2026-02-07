package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Sinks;

/**
 * Tracks provider iteration state during streaming with pre-first-token fallback.
 */
record StreamingAttemptContext(
        List<OpenAiProviderCandidate> availableProviders, int providerIndex, Sinks.Many<StreamingNotice> noticeSink) {
    StreamingAttemptContext {
        Objects.requireNonNull(availableProviders, "availableProviders");
        Objects.requireNonNull(noticeSink, "noticeSink");
    }

    OpenAiProviderCandidate currentProvider() {
        return availableProviders.get(providerIndex);
    }

    int currentAttempt() {
        return providerIndex + 1;
    }

    int maxAttempts() {
        return availableProviders.size();
    }

    boolean hasNextProvider() {
        return providerIndex + 1 < availableProviders.size();
    }

    StreamingAttemptContext withNextProvider() {
        return new StreamingAttemptContext(availableProviders, providerIndex + 1, noticeSink);
    }
}
