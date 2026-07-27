package com.williamcallahan.javachat.application.ingestion;

/**
 * Bounds the number of remote documentation pages inspected by one ingestion run.
 *
 * @param maximumPages positive page bound
 */
public record PageLimit(int maximumPages) {

    /**
     * Rejects non-positive bounds before they enter the ingestion application boundary.
     *
     * @throws IllegalArgumentException when {@code maximumPages} is not positive
     */
    public PageLimit {
        if (maximumPages <= 0) {
            throw new IllegalArgumentException("Page limit must be greater than 0");
        }
    }
}
