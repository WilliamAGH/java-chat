package com.williamcallahan.javachat.web;

/**
 * Represents a standardized JSON error payload returned by API endpoints.
 *
 * @param status fixed status indicator (typically "error")
 * @param message user-facing error message
 * @param details optional diagnostic details suitable for clients
 */
public record ApiErrorResponse(String status, String message, String details) implements ApiResponse {

    /**
     * Creates an error response with no diagnostic details.
     *
     * @param message user-facing error message
     * @return standardized error payload
     */
    public static ApiErrorResponse error(String message) {
        return new ApiErrorResponse("error", message, null);
    }

    /**
     * Creates an error response including diagnostic details.
     *
     * @param message user-facing error message
     * @param details diagnostic details suitable for clients
     * @return standardized error payload
     */
    public static ApiErrorResponse error(String message, String details) {
        return new ApiErrorResponse("error", message, details);
    }
}
