package com.williamcallahan.javachat.domain.errors;

/**
 * Defines the shared contract for JSON API responses so controllers can return consistent payloads.
 *
 * <p>The response contract is intentionally framework-free to keep domain models reusable across
 * delivery mechanisms.</p>
 */
public sealed interface ApiResponse permits ApiErrorResponse, ApiSuccessResponse {

    /**
     * Returns the status indicator for this response.
     *
     * @return response status for client handling
     */
    String status();
}
