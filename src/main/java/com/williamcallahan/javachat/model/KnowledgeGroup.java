package com.williamcallahan.javachat.model;

import java.util.Objects;

/**
 * One ingested body of document knowledge within a Qdrant collection.
 *
 * <p>A group is the durable unit a user can reason about — a documentation set, a book
 * cohort, an article series, or one indexed GitHub repository — rather than a single
 * ingestion run, which leaves no stored identifier.</p>
 *
 * @param collection Qdrant collection holding the group
 * @param kind collection taxonomy label (BOOKS, DOCS, ARTICLES, PDFS, or GITHUB)
 * @param name group identifier: documentation-set token or repository URL
 * @param chunks embedded chunks the group contains
 */
public record KnowledgeGroup(String collection, String kind, String name, long chunks) {

    public KnowledgeGroup {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (collection.isBlank() || kind.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("collection, kind, and name must not be blank");
        }
        if (chunks < 0) {
            throw new IllegalArgumentException("chunks must not be negative");
        }
    }
}
