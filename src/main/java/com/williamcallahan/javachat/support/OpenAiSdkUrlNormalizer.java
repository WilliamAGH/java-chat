package com.williamcallahan.javachat.support;

/**
 * Normalizes base URLs for the OpenAI Java SDK.
 *
 * <p>The SDK expects base URLs to end with the API version prefix (e.g., /v1)
 * and does not accept an embedding endpoint suffix as its base URL.</p>
 */
public final class OpenAiSdkUrlNormalizer {

    private static final String TRAILING_SLASH = "/";
    private static final String V1_SUFFIX = "/v1";
    private static final String EMBEDDINGS_SUFFIX = "/embeddings";
    private static final String V1_EMBEDDINGS_SUFFIX = V1_SUFFIX + EMBEDDINGS_SUFFIX;
    private static final String SHARED_LLM_GATEWAY_BASE_URL = "https://api.llm-gateway.iocloudhost.net/v1";

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
        String trimmedBaseUrl = baseUrl.trim();
        String normalizedBaseUrl = normalizeInternal(trimmedBaseUrl);
        requireGatewayEndpoint(normalizedBaseUrl);
        return normalizedBaseUrl;
    }

    private static void requireGatewayEndpoint(String baseUrl) {
        if (!SHARED_LLM_GATEWAY_BASE_URL.equals(baseUrl)) {
            throw new IllegalStateException("OPENAI_BASE_URL must be https://api.llm-gateway.iocloudhost.net/v1");
        }
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
        if (trimmed.endsWith(V1_SUFFIX)) {
            return trimmed;
        }
        return trimmed + V1_SUFFIX;
    }
}
