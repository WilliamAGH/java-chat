package com.williamcallahan.javachat.application.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;

/** Defines the application boundary for on-disk documentation mirror ingestion. */
public interface LocalDocumentationIngestionUseCase {

    /**
     * Ingests an on-disk documentation mirror within the requested file bound.
     *
     * @param rootDirectory documentation mirror root
     * @param fileLimit validated maximum number of files to inspect
     * @return complete local ingestion outcome
     * @throws IOException when the mirror cannot be enumerated or read
     */
    IngestionLocalOutcome ingestLocalDirectory(String rootDirectory, FileLimit fileLimit) throws IOException;
}
