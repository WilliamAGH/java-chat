package com.williamcallahan.javachat.web;

/**
 * Represents a standardized JSON success payload returned by API endpoints.
 *
 * @param status fixed status indicator (typically "success")
 * @param message user-facing success message
 */
public record ApiSuccessResponse(String status, String message) implements ApiResponse {

    /**
     * Creates a success response payload.
     *
     * @param message user-facing success message
     * @return standardized success payload
     */
    public static ApiSuccessResponse success(String message) {
        return new ApiSuccessResponse("success", message);
    }
}
