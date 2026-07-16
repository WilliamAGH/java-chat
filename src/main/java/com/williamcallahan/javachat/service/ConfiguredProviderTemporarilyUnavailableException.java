package com.williamcallahan.javachat.service;

import java.util.Objects;

/**
 * Signals that the configured provider cannot accept a request now but can become available without reconfiguration.
 *
 * <p>This distinguishes provider backoff and rate-limit admission denials from a missing configured client, which is
 * a fatal configuration failure.</p>
 */
final class ConfiguredProviderTemporarilyUnavailableException extends IllegalStateException {
    private final RateLimitService.ApiProvider provider;

    /**
     * Creates a retryable provider-admission failure for the configured provider.
     *
     * @param provider provider that temporarily rejected admission
     */
    ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider provider) {
        super(messageFor(provider));
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Returns the provider that temporarily rejected admission.
     *
     * @return configured provider subject to backoff or rate limiting
     */
    RateLimitService.ApiProvider provider() {
        return provider;
    }

    private static String messageFor(RateLimitService.ApiProvider provider) {
        RateLimitService.ApiProvider requiredProvider = Objects.requireNonNull(provider, "provider");
        return "Configured LLM provider is temporarily unavailable: " + requiredProvider.getName();
    }
}
