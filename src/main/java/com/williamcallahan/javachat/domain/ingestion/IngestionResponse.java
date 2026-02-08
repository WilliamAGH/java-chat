package com.williamcallahan.javachat.domain.ingestion;

/**
 * Defines the response contract for remote ingestion operations so callers can discriminate outcomes.
 */
public sealed interface IngestionResponse permits IngestionRunOutcome, IngestionErrorResponse {

    /**
     * Returns the status indicator for this ingestion response.
     *
     * @return response status for ingestion operations
     */
    String status();
}
