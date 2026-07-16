package com.williamcallahan.javachat.web;

/**
 * Request body for chat streaming endpoint.
 *
 * @param sessionId Optional session identifier for conversation continuity
 * @param latest The user's chat message
 */
public record ChatStreamRequest(String sessionId, String latest) {
    private static final String GENERATED_SESSION_PREFIX = "chat-";
    private static final String LATEST_QUERY_REQUIRED_MESSAGE = "Latest query is required";

    /**
     * Creates a request whose canonical query contains non-whitespace text.
     *
     * @throws IllegalArgumentException when the latest query is null or blank
     */
    public ChatStreamRequest {
        if (latest == null || latest.isBlank()) {
            throw new IllegalArgumentException(LATEST_QUERY_REQUIRED_MESSAGE);
        }
    }

    /**
     * Returns a valid session ID, generating one if not provided.
     */
    public String resolvedSessionId() {
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }
        return GENERATED_SESSION_PREFIX + java.util.UUID.randomUUID();
    }
}
