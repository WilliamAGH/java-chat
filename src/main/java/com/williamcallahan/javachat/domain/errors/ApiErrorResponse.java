package com.williamcallahan.javachat.domain.errors;

import java.util.Objects;

/**
 * Describes a standard JSON error payload that API clients can interpret uniformly.
 *
 * @param status fixed status indicator (typically "error")
 * @param message user-facing error message
 * @param details optional diagnostic details suitable for clients
 */
public record ApiErrorResponse(String status, String message, String details) implements ApiResponse {
    private static final String STATUS_ERROR = "error";

    public ApiErrorResponse {
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(message, "Error message is required");
    }

    /**
     * Creates an error response with no diagnostic details.
     *
     * @param message user-facing error message
     * @return standardized error payload
     */
    public static ApiErrorResponse error(String message) {
        return new ApiErrorResponse(STATUS_ERROR, message, null);
    }

    /**
     * Creates an error response including diagnostic details.
     *
     * @param message user-facing error message
     * @param details diagnostic details suitable for clients
     * @return standardized error payload
     */
    public static ApiErrorResponse error(String message, String details) {
        return new ApiErrorResponse(STATUS_ERROR, message, details);
    }
}
