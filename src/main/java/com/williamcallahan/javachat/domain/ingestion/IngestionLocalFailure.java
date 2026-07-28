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

    /**
     * Indicates whether this failure is isolated to one source file and later files remain safe to attempt.
     *
     * <p>Unknown phases fail closed. Remote persistence, embedding, marker, and collection-state
     * failures therefore stop the run instead of returning best-effort ingestion.</p>
     *
     * @return true only for extraction and chunk-construction failures scoped to one file
     */
    public boolean allowsFollowingFileAttempt() {
        return switch (phase) {
            case "filename", "file-attributes", "pdf-extraction", "html-read", "empty-document", "content-guard" ->
                true;
            default -> false;
        };
    }
}
