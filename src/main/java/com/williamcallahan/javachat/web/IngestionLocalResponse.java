package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.DocsIngestionService.LocalIngestionFailure;
import java.util.List;

/**
 * Represents a standardized JSON payload for local ingestion operations.
 *
 * @param status status indicator ("success" or "partial-success")
 * @param processed number of processed files
 * @param dir ingested directory path
 * @param failures per-file failures encountered during ingestion
 */
public record IngestionLocalResponse(String status,
                                     int processed,
                                     String dir,
                                     List<LocalIngestionFailure> failures)
        implements ApiResponse {

    public IngestionLocalResponse {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates a local ingestion success response.
     *
     * @param processed number of processed files
     * @param dir ingested directory path
     * @param failures per-file failures encountered during ingestion
     * @return standardized local ingestion payload
     */
    public static IngestionLocalResponse success(int processed,
                                                 String dir,
                                                 List<LocalIngestionFailure> failures) {
        String status = failures == null || failures.isEmpty() ? "success" : "partial-success";
        return new IngestionLocalResponse(status, processed, dir, failures);
    }

    /**
     * Returns per-file ingestion failures as an unmodifiable snapshot.
     */
    @Override
    public List<LocalIngestionFailure> failures() {
        return List.copyOf(failures);
    }
}
