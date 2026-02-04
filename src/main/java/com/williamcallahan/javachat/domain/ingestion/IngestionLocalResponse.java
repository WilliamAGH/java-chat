package com.williamcallahan.javachat.domain.ingestion;

/**
 * Defines the response contract for local ingestion operations so clients can interpret outcomes.
 */
public sealed interface IngestionLocalResponse permits IngestionLocalOutcome, IngestionErrorResponse {

    /**
     * Returns the status indicator for this local ingestion response.
     *
     * @return response status for local ingestion operations
     */
    String status();
}
