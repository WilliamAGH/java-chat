package com.williamcallahan.javachat.web;

/**
 * Request body for guided learning streaming endpoint.
 *
 * @param sessionId Session identifier for conversation continuity (prefixed with "guided:")
 * @param slug The lesson slug identifier
 * @param latest The user's chat message
 */
public record GuidedStreamRequest(
    String sessionId,
    String slug,
    String latest
) {
    /**
     * Returns a valid session ID, using default if not provided.
     */
    public String resolvedSessionId() {
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }
        return "guided:default";
    }

    /**
     * Returns the user query, defaulting to empty string if null.
     */
    public String userQuery() {
        return latest != null ? latest : "";
    }

    /**
     * Returns the lesson slug, defaulting to empty string if null.
     */
    public String lessonSlug() {
        return slug != null ? slug : "";
    }
}
