package com.williamcallahan.javachat.web;

/**
 * Request body for the deprecated process-structured endpoint.
 *
 * @param text The markdown text to process
 * @deprecated This endpoint is scheduled for removal in favor of unified processing.
 */
@Deprecated(since = "1.0", forRemoval = true)
public record ProcessStructuredRequest(String text) {}
