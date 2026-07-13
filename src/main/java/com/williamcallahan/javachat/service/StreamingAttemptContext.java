package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Sinks;

/**
 * Tracks bounded provider attempts during streaming before response text is emitted.
 */
record StreamingAttemptContext(
        List<OpenAiProviderCandidate> availableProviders,
        int attemptIndex,
        Sinks.Many<StreamingNotice> noticeSink,
        Sinks.One<RateLimitService.ApiProvider> providerChangeSink) {
    StreamingAttemptContext {
        Objects.requireNonNull(availableProviders, "availableProviders");
        Objects.requireNonNull(noticeSink, "noticeSink");
        Objects.requireNonNull(providerChangeSink, "providerChangeSink");
        if (availableProviders.isEmpty()) {
            throw new IllegalArgumentException("availableProviders cannot be empty");
        }

        int maxAttempts = maximumAttemptsFor(availableProviders);
        if (attemptIndex < 0 || attemptIndex >= maxAttempts) {
            throw new IllegalArgumentException("attemptIndex is outside the bounded attempt range");
        }
    }

    static StreamingAttemptContext first(
            List<OpenAiProviderCandidate> availableProviders,
            Sinks.Many<StreamingNotice> noticeSink,
            Sinks.One<RateLimitService.ApiProvider> providerChangeSink) {
        return new StreamingAttemptContext(availableProviders, 0, noticeSink, providerChangeSink);
    }

    OpenAiProviderCandidate currentProvider() {
        return availableProviders.get(attemptIndex);
    }

    int currentAttempt() {
        return attemptIndex + 1;
    }

    int maxAttempts() {
        return maximumAttemptsFor(availableProviders);
    }

    boolean hasNextAttempt() {
        return currentAttempt() < maxAttempts();
    }

    StreamingAttemptContext withNextAttempt() {
        if (!hasNextAttempt()) {
            throw new IllegalStateException("streaming attempts are exhausted");
        }

        return new StreamingAttemptContext(availableProviders, attemptIndex + 1, noticeSink, providerChangeSink);
    }

    private static int maximumAttemptsFor(List<OpenAiProviderCandidate> availableProviders) {
        return availableProviders.size();
    }
}
