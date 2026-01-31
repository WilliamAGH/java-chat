package com.williamcallahan.javachat.web;

/**
 * Request body for chat streaming endpoint.
 * Supports both "message" (API/curl) and "latest" (web UI) field names for the user query.
 *
 * @param sessionId Optional session identifier for conversation continuity
 * @param message The user's chat message (preferred field name for API clients)
 * @param latest The user's chat message (alternative field name used by web UI)
 */
public record ChatStreamRequest(String sessionId, String message, String latest) {
    private static final String GENERATED_SESSION_PREFIX = "chat-";

    /**
     * Returns the user query, preferring "message" over "latest" field.
     */
    public String userQuery() {
        if (message != null && !message.isEmpty()) {
            return message;
        }
        return latest != null ? latest : "";
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
