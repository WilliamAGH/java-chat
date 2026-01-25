package com.williamcallahan.javachat.support;

/**
 * Normalizes base URLs for the OpenAI Java SDK.
 *
 * <p>The SDK expects base URLs to end with the API version prefix (e.g., /v1).
 * Handles provider-specific formats including GitHub Models (/inference) and
 * embedding endpoint suffixes.</p>
 */
public final class OpenAiSdkUrlNormalizer {

    private OpenAiSdkUrlNormalizer() {}

    /**
     * Normalizes a base URL for the OpenAI Java SDK.
     *
     * @param baseUrl raw base URL from configuration
     * @return normalized URL suitable for OpenAIOkHttpClient.builder().baseUrl()
     * @throws IllegalStateException if baseUrl is null or blank
     */
    public static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("OpenAI SDK base URL is not configured");
        }
        return normalizeInternal(baseUrl.trim());
    }

    /**
     * Nullable-safe variant that returns input unchanged if blank.
     * Use when null URLs indicate "provider not configured" rather than error.
     *
     * @param baseUrl raw base URL from configuration, may be null
     * @return normalized URL, or null/blank if input was null/blank
     */
    public static String normalizeOrNull(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        return normalizeInternal(baseUrl.trim());
    }

    private static String normalizeInternal(String trimmed) {
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/v1/embeddings")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/embeddings".length());
        } else if (trimmed.endsWith("/embeddings")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/embeddings".length());
        }
        if (trimmed.endsWith("/inference")) {
            return trimmed + "/v1";
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed;
        }
        return trimmed + "/v1";
    }
}
