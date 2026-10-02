package com.williamcallahan.javachat.domain.knowledge;

import java.util.List;
import java.util.Objects;

/** Describes one retrievable knowledge cohort and its exact stored chunk count. */
public record KnowledgeGroup(
        String collection,
        Kind kind,
        String name,
        List<String> canonicalUrls,
        List<String> ingestedVersions,
        long chunks) {
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
        canonicalUrls = copyText(canonicalUrls, "canonicalUrls");
        if (canonicalUrls.isEmpty()) {
            throw new IllegalArgumentException("canonicalUrls must not be empty");
        }
        ingestedVersions = copyText(ingestedVersions, "ingestedVersions");
        if (chunks < 0) {
            throw new IllegalArgumentException("chunks must not be negative");
        }
    }

    @Override
    public List<String> canonicalUrls() {
        return List.copyOf(canonicalUrls);
    }

    @Override
    public List<String> ingestedVersions() {
        return List.copyOf(ingestedVersions);
    }

    private static List<String> copyText(List<String> text, String fieldName) {
        Objects.requireNonNull(text, fieldName);
        return text.stream()
                .map(value -> requireText(value, fieldName))
                .distinct()
                .sorted()
                .toList();
    }

    private static String requireText(String text, String fieldName) {
        Objects.requireNonNull(text, fieldName);
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
