package com.williamcallahan.javachat.domain.ingestion;

import java.util.Objects;

/**
 * Represents the outcome of a remote ingestion run so callers can confirm completion.
 *
 * @param status status indicator for the ingestion run
 * @param message user-facing completion message
 */
public record IngestionRunOutcome(String status, String message) implements IngestionResponse {
    private static final String STATUS_SUCCESS = "success";

    public IngestionRunOutcome {
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(message, "Ingestion message is required");
    }

    /**
     * Creates a success outcome for a completed ingestion run.
     *
     * @param message user-facing completion message
     * @return standardized ingestion run outcome
     */
    public static IngestionRunOutcome success(String message) {
        return new IngestionRunOutcome(STATUS_SUCCESS, message);
    }
}
