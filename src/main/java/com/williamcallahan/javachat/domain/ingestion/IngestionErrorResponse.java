package com.williamcallahan.javachat.domain.ingestion;

import java.util.Objects;

/**
 * Describes an ingestion error response so API clients can react consistently.
 *
 * @param status fixed status indicator (typically "error")
 * @param message user-facing error message
 * @param details optional diagnostic details suitable for clients
 */
public record IngestionErrorResponse(String status, String message, String details)
        implements IngestionResponse, IngestionLocalResponse {
    private static final String STATUS_ERROR = "error";

    public IngestionErrorResponse {
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(message, "Error message is required");
    }

    /**
     * Creates an error response with no diagnostic details.
     *
     * @param message user-facing error message
     * @return standardized ingestion error payload
     */
    public static IngestionErrorResponse error(String message) {
        return new IngestionErrorResponse(STATUS_ERROR, message, null);
    }

    /**
     * Creates an error response including diagnostic details.
     *
     * @param message user-facing error message
     * @param details diagnostic details suitable for clients
     * @return standardized ingestion error payload
     */
    public static IngestionErrorResponse error(String message, String details) {
        return new IngestionErrorResponse(STATUS_ERROR, message, details);
    }
}
