package com.williamcallahan.javachat.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Owns the validated cooldown applied after a transient configured-provider failure.
 *
 * <p>The configuration key and supported bounds live with this value type so binding,
 * routing, and tests project one operational policy.</p>
 *
 * @param duration validated cooldown duration
 */
public record ConfiguredProviderBackoff(Duration duration) {
    /** Configuration key bound by Spring for the configured-provider cooldown. */
    public static final String CONFIGURED_PROVIDER_BACKOFF_CONFIGURATION_KEY =
            "app.llm.configured-provider-backoff-seconds";

    /** Smallest supported configured-provider cooldown in seconds. */
    public static final long MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 1L;

    /** Largest supported configured-provider cooldown in seconds. */
    public static final long MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 86_400L;

    /**
     * Rejects durations that cannot form the configured-provider routing policy.
     *
     * @throws NullPointerException when duration is null
     * @throws IllegalArgumentException when duration is outside the supported whole-second range
     */
    public ConfiguredProviderBackoff {
        Objects.requireNonNull(duration, "duration");
        long configuredProviderBackoffSeconds = duration.toSeconds();
        if (duration.getNano() != 0
                || configuredProviderBackoffSeconds < MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                || configuredProviderBackoffSeconds > MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS) {
            throw invalidConfiguredProviderBackoff(configuredProviderBackoffSeconds);
        }
    }

    /**
     * Creates the configured-provider cooldown from Spring's seconds-based property.
     *
     * @param configuredProviderBackoffSeconds configured cooldown in seconds
     * @return validated configured-provider cooldown
     * @throws IllegalArgumentException when the configured seconds are outside the supported range
     */
    public static ConfiguredProviderBackoff fromSeconds(long configuredProviderBackoffSeconds) {
        if (configuredProviderBackoffSeconds < MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                || configuredProviderBackoffSeconds > MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS) {
            throw invalidConfiguredProviderBackoff(configuredProviderBackoffSeconds);
        }
        return new ConfiguredProviderBackoff(Duration.ofSeconds(configuredProviderBackoffSeconds));
    }

    private static IllegalArgumentException invalidConfiguredProviderBackoff(long configuredProviderBackoffSeconds) {
        return new IllegalArgumentException(CONFIGURED_PROVIDER_BACKOFF_CONFIGURATION_KEY
                + " must be in range ["
                + MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                + ", "
                + MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                + "], got: "
                + configuredProviderBackoffSeconds);
    }
}
