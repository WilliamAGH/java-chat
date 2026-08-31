package com.williamcallahan.javachat.domain.knowledge;

import java.util.Objects;

/** Describes one retrievable knowledge cohort and its exact stored chunk count. */
public record KnowledgeGroup(String collection, Kind kind, String name, long chunks) {
    /** Closed inventory taxonomy exposed by the API and CLI. */
    public enum Kind {
        BOOKS,
        DOCS,
        ARTICLES,
        PDFS,
        GITHUB
    }

    /** Validates the stable inventory identity and nonnegative count. */
    public KnowledgeGroup {
        collection = requireText(collection, "collection");
        kind = Objects.requireNonNull(kind, "kind");
        name = requireText(name, "name");
        if (chunks < 0) {
            throw new IllegalArgumentException("chunks must not be negative");
        }
    }

    private static String requireText(String text, String fieldName) {
        Objects.requireNonNull(text, fieldName);
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
