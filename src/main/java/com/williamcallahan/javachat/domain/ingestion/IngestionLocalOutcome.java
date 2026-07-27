package com.williamcallahan.javachat.domain.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

/**
 * Represents the outcome of a local ingestion run so operators can assess partial failures.
 *
 * @param status status indicator ("success" or "partial-success")
 * @param processed number of processed files
 * @param dir ingested directory path
 * @param failures per-file failures encountered during ingestion
 * @param backlog internal durable accounting for CLI postconditions and resume
 */
public record IngestionLocalOutcome(
        String status,
        int processed,
        String dir,
        List<IngestionLocalFailure> failures,
        @JsonIgnore IngestionBacklogStatus backlog)
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
        Objects.requireNonNull(backlog, "Ingestion backlog is required");
        if (failures.size() != backlog.failedFiles()) {
            throw new IllegalArgumentException("Failure details must match the failed backlog count");
        }
    }

    /**
     * Creates a local ingestion success response.
     *
     * @param backlog durable progress snapshot
     * @param dir ingested directory path
     * @param failures per-file failures encountered during ingestion
     * @return standardized local ingestion outcome
     */
    public static IngestionLocalOutcome fromBacklog(
            IngestionBacklogStatus backlog, String dir, List<IngestionLocalFailure> failures) {
        IngestionBacklogStatus requiredBacklog = Objects.requireNonNull(backlog, "backlog");
        String outcomeStatus = requiredBacklog.lifecycle() == IngestionBacklogStatus.Lifecycle.COMPLETE
                ? STATUS_SUCCESS
                : STATUS_PARTIAL_SUCCESS;
        return new IngestionLocalOutcome(
                outcomeStatus, requiredBacklog.processedFiles(), dir, failures, requiredBacklog);
    }
}
