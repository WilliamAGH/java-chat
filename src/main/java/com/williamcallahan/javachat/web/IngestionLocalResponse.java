package com.williamcallahan.javachat.web;

/**
 * Represents a standardized JSON payload for local ingestion operations.
 *
 * @param status fixed status indicator (typically "success")
 * @param processed number of processed files
 * @param dir ingested directory path
 */
public record IngestionLocalResponse(String status, int processed, String dir) implements ApiResponse {

    /**
     * Creates a local ingestion success response.
     *
     * @param processed number of processed files
     * @param dir ingested directory path
     * @return standardized local ingestion payload
     */
    public static IngestionLocalResponse success(int processed, String dir) {
        return new IngestionLocalResponse("success", processed, dir);
    }
}
