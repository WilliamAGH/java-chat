package com.williamcallahan.javachat.web;

/**
 * Response for embeddings health check endpoint.
 *
 * @param localEmbeddingEnabled Whether local embedding is enabled
 * @param serverUrl The embedding server URL
 * @param status Health status: "healthy", "unhealthy", or "disabled"
 * @param serverReachable Whether the server is reachable (null if disabled)
 * @param error Error message if unhealthy
 */
public record EmbeddingsHealthResponse(
    boolean localEmbeddingEnabled,
    String serverUrl,
    String status,
    Boolean serverReachable,
    String error
) {
    /**
     * Creates a healthy response.
     */
    public static EmbeddingsHealthResponse healthy(String serverUrl) {
        return new EmbeddingsHealthResponse(true, serverUrl, "healthy", true, null);
    }

    /**
     * Creates an unhealthy response.
     */
    public static EmbeddingsHealthResponse unhealthy(String serverUrl, String errorMessage) {
        return new EmbeddingsHealthResponse(true, serverUrl, "unhealthy", false, errorMessage);
    }

    /**
     * Creates a disabled response.
     */
    public static EmbeddingsHealthResponse disabled(String serverUrl) {
        return new EmbeddingsHealthResponse(false, serverUrl, "disabled", null, null);
    }
}
