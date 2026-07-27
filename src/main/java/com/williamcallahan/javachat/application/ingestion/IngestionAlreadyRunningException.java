package com.williamcallahan.javachat.application.ingestion;

/**
 * Signals that another process already owns the local documentation ingestion run.
 */
public final class IngestionAlreadyRunningException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a conflict without exposing lock-file or filesystem details.
     */
    public IngestionAlreadyRunningException() {
        super("Local documentation ingestion is already running");
    }
}
