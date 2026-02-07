package com.williamcallahan.javachat.service;

/**
 * Identifies where a streaming notice originated in the provider attempt chain.
 *
 * @param provider display name of the provider that triggered this notice
 * @param stage processing stage for UI grouping (e.g., "stream")
 * @param attempt current attempt number (1-based)
 * @param maxAttempts total configured provider attempts for the request
 */
record StreamingNoticeOrigin(String provider, String stage, int attempt, int maxAttempts) {
    StreamingNoticeOrigin {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("stage cannot be null or blank");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (maxAttempts <= 0 || attempt > maxAttempts) {
            throw new IllegalArgumentException("attempt must be <= maxAttempts");
        }
        provider = provider == null ? "" : provider;
    }
}
