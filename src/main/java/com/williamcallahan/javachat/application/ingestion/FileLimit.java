package com.williamcallahan.javachat.application.ingestion;

/**
 * Bounds the number of local documentation files inspected by one ingestion run.
 *
 * @param maximumFiles positive file bound
 */
public record FileLimit(int maximumFiles) {

    /**
     * Rejects non-positive bounds before they enter the ingestion application boundary.
     *
     * @throws IllegalArgumentException when {@code maximumFiles} is not positive
     */
    public FileLimit {
        if (maximumFiles <= 0) {
            throw new IllegalArgumentException("File limit must be greater than 0");
        }
    }
}
