package com.williamcallahan.javachat.web;

/**
 * Represents the common contract for JSON API responses returned by controllers.
 *
 * <p>API responses always include a status indicator so clients can handle success and error
 * payloads uniformly.
 */
public sealed interface ApiResponse permits ApiErrorResponse, ApiSuccessResponse, IngestionLocalResponse {

    /**
     * Returns the status indicator for this response.
     *
     * @return response status (for example, "success" or "error")
     */
    String status();
}

