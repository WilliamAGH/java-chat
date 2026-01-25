package com.williamcallahan.javachat.web;

/**
 * Response for session validation endpoint.
 * Allows frontends to detect session drift after server restarts.
 *
 * @param sessionId the session identifier that was validated
 * @param turnCount number of conversation turns (user + assistant messages) on server
 * @param exists true if the session has any history on the server
 * @param message human-readable status message
 */
public record SessionValidationResponse(String sessionId, int turnCount, boolean exists, String message) {}
