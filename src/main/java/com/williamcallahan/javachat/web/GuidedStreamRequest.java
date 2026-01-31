package com.williamcallahan.javachat.web;

import java.util.Optional;

/**
 * Request body for guided learning streaming endpoint.
 *
 * @param sessionId Session identifier for conversation continuity (prefixed with "guided:")
 * @param slug The lesson slug identifier
 * @param latest The user's chat message
 */
public record GuidedStreamRequest(String sessionId, String slug, String latest) {
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
     * Returns the user query when present and non-blank.
     *
     * <p>Callers should use {@link Optional#orElseThrow} or {@link Optional#orElse}
     * to handle the missing case explicitly, avoiding silent empty-string defaults.</p>
     *
     * @return the user's query if present and non-blank
     */
    public Optional<String> userQuery() {
        return Optional.ofNullable(latest).filter(s -> !s.isBlank());
    }

    /**
     * Returns the lesson slug when present and non-blank.
     *
     * @return the lesson slug if present and non-blank
     */
    public Optional<String> lessonSlug() {
        return Optional.ofNullable(slug).filter(s -> !s.isBlank());
    }
}
