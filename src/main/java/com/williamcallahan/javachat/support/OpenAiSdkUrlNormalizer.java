package com.williamcallahan.javachat.support;

/**
 * Normalizes base URLs for the OpenAI Java SDK.
 *
 * <p>The SDK expects base URLs to end with the API version prefix (e.g., /v1).
 * Handles provider-specific formats including GitHub Models (/inference) and
 * embedding endpoint suffixes.</p>
 */
public final class OpenAiSdkUrlNormalizer {

    private static final String TRAILING_SLASH = "/";
    private static final String V1_SUFFIX = "/v1";
    private static final String EMBEDDINGS_SUFFIX = "/embeddings";
    private static final String V1_EMBEDDINGS_SUFFIX = V1_SUFFIX + EMBEDDINGS_SUFFIX;
    private static final String INFERENCE_SUFFIX = "/inference";

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

    private static String normalizeInternal(String trimmed) {
        if (trimmed.endsWith(TRAILING_SLASH)) {
            trimmed = trimmed.substring(0, trimmed.length() - TRAILING_SLASH.length());
        }
        if (trimmed.endsWith(V1_EMBEDDINGS_SUFFIX)) {
            trimmed = trimmed.substring(0, trimmed.length() - EMBEDDINGS_SUFFIX.length());
        } else if (trimmed.endsWith(EMBEDDINGS_SUFFIX)) {
            trimmed = trimmed.substring(0, trimmed.length() - EMBEDDINGS_SUFFIX.length());
        }
        if (trimmed.endsWith(INFERENCE_SUFFIX)) {
            return trimmed + V1_SUFFIX;
        }
        if (trimmed.endsWith(V1_SUFFIX)) {
            return trimmed;
        }
        return trimmed + V1_SUFFIX;
    }
}
