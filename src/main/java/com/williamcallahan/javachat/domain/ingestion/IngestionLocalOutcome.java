package com.williamcallahan.javachat.domain.ingestion;

import java.util.List;
import java.util.Objects;

/**
 * Represents the outcome of a local ingestion run so operators can assess partial failures.
 *
 * @param status status indicator ("success" or "partial-success")
 * @param processed number of processed files
 * @param dir ingested directory path
 * @param failures per-file failures encountered during ingestion
 */
public record IngestionLocalOutcome(String status, int processed, String dir, List<IngestionLocalFailure> failures)
        implements IngestionLocalResponse {
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_PARTIAL_SUCCESS = "partial-success";

    public IngestionLocalOutcome {
        Objects.requireNonNull(status, "Status is required");
        if (processed < 0) {
            throw new IllegalArgumentException("Processed count must be non-negative");
        }
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("Ingested directory is required");
        }
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * Creates a local ingestion success response.
     *
     * @param processed number of processed files
     * @param dir ingested directory path
     * @param failures per-file failures encountered during ingestion
     * @return standardized local ingestion outcome
     */
    public static IngestionLocalOutcome success(int processed, String dir, List<IngestionLocalFailure> failures) {
        boolean hasFailures = failures != null && !failures.isEmpty();
        String status = hasFailures ? STATUS_PARTIAL_SUCCESS : STATUS_SUCCESS;
        return new IngestionLocalOutcome(status, processed, dir, failures);
    }
}
