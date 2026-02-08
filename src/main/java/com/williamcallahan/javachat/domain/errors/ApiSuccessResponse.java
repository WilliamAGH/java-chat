package com.williamcallahan.javachat.domain.errors;

import java.util.Objects;

/**
 * Describes a standard JSON success payload so API clients can handle completions consistently.
 *
 * @param status fixed status indicator (typically "success")
 * @param message user-facing success message
 */
public record ApiSuccessResponse(String status, String message) implements ApiResponse {
    private static final String STATUS_SUCCESS = "success";

    public ApiSuccessResponse {
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(message, "Success message is required");
    }

    /**
     * Creates a success response payload.
     *
     * @param message user-facing success message
     * @return standardized success payload
     */
    public static ApiSuccessResponse success(String message) {
        return new ApiSuccessResponse(STATUS_SUCCESS, message);
    }
}
