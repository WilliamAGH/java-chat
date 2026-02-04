package com.williamcallahan.javachat.domain.ingestion;

import java.util.Objects;

/**
 * Captures a single local ingestion failure with file and phase context so triage is faster.
 *
 * @param filePath absolute file path
 * @param phase ingestion phase that failed
 * @param details failure details for diagnostics
 */
public record IngestionLocalFailure(String filePath, String phase, String details) {

    public IngestionLocalFailure {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path is required");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("Failure phase is required");
        }
        Objects.requireNonNull(details, "Failure details are required");
    }
}
