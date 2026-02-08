package com.williamcallahan.javachat.domain.ingestion;

import com.williamcallahan.javachat.service.ingestion.LocalDocsFileOutcome;
import java.util.Objects;

/**
 * Pairs a file ingestion outcome with the authoritative source URL for that file.
 *
 * <p>Returned by {@code SourceCodeFileIngestionProcessor.process()} so that callers
 * (e.g., {@code GitHubRepoProcessor}) can collect all file URLs encountered during
 * a repository walk. The complete URL set enables deleted-file detection by diffing
 * against URLs stored in Qdrant.</p>
 *
 * @param outcome ingestion outcome (processed, skipped, or failed)
 * @param fileUrl authoritative GitHub blob URL for this source file
 */
public record SourceFileProcessingResult(LocalDocsFileOutcome outcome, String fileUrl) {

    /**
     * Validates both fields are non-null and the URL is non-blank.
     */
    public SourceFileProcessingResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(fileUrl, "fileUrl");
        if (fileUrl.isBlank()) {
            throw new IllegalArgumentException("fileUrl must not be blank");
        }
    }
}
