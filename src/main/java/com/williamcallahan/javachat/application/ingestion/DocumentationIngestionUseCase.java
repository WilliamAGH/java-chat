package com.williamcallahan.javachat.application.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;

/** Defines the application boundary for remote and mirrored documentation ingestion runs. */
public interface DocumentationIngestionUseCase {

    /**
     * Crawls the configured remote documentation source within the requested page bound.
     *
     * @param pageLimit validated maximum number of pages to ingest
     * @throws IOException when a source page or local snapshot cannot be read or written
     */
    void crawlAndIngest(PageLimit pageLimit) throws IOException;

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
