package com.williamcallahan.javachat.application.ingestion;

import java.io.IOException;

/** Defines the application boundary for remote documentation ingestion runs. */
public interface DocumentationIngestionUseCase {

    /**
     * Crawls the configured remote documentation source within the requested page bound.
     *
     * @param pageLimit validated maximum number of pages to ingest
     * @throws IOException when a source page or local snapshot cannot be read or written
     */
    void crawlAndIngest(PageLimit pageLimit) throws IOException;
}
