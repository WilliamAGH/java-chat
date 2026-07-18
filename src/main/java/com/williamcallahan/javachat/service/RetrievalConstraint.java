package com.williamcallahan.javachat.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes optional metadata constraints that can be pushed into Qdrant queries.
 *
 * <p>Pushing constraints down to the vector database improves relevance and reduces
 * candidate waste versus filtering only after retrieval.</p>
 *
 * @param docVersion document version token (for example {@code 25}) or empty
 * @param sourceKind source kind token (for example {@code official}) or empty
 * @param docType document type token (for example {@code api-docs}) or empty
 * @param sourceName source name token (for example {@code oracle}) or empty
 * @param docSet documentation-set tokens matched with any-of semantics, or empty
 */
public record RetrievalConstraint(
        String docVersion, String sourceKind, String docType, String sourceName, List<String> docSet) {

    /**
     * Creates a normalized retrieval constraint.
     */
    public RetrievalConstraint {
        docVersion = sanitize(docVersion);
        sourceKind = sanitize(sourceKind);
        docType = sanitize(docType);
        sourceName = sanitize(sourceName);
        docSet = List.copyOf(sanitizeDocSets(docSet));
    }

    /**
     * Returns an unconstrained retrieval constraint.
     *
     * @return unconstrained retrieval constraint
     */
    public static RetrievalConstraint none() {
        return new RetrievalConstraint("", "", "", "", List.of());
    }

    /**
     * Returns a version-only retrieval constraint.
     *
     * @param docVersion document version token
     * @return retrieval constraint containing only doc version
     */
    public static RetrievalConstraint forDocVersion(String docVersion) {
        return new RetrievalConstraint(docVersion, "", "", "", List.of());
    }

    /**
     * Returns a constraint limited to the official documentation sets allowed by a guided lesson.
     *
     * @param docSet canonical documentation-set tokens
     * @return official-source retrieval constraint
     */
    public static RetrievalConstraint forOfficialDocSets(List<String> docSet) {
        RetrievalConstraint officialDocSetConstraint = new RetrievalConstraint("", "official", "", "", docSet);
        if (officialDocSetConstraint.docSet().isEmpty()) {
            throw new IllegalArgumentException("Official docSet constraint cannot be empty");
        }
        return officialDocSetConstraint;
    }

    /**
     * Returns true when at least one server-side filter can be applied.
     *
     * @return true when the constraint has one or more non-empty fields
     */
    public boolean hasServerSideConstraint() {
        return !docVersion.isBlank()
                || !sourceKind.isBlank()
                || !docType.isBlank()
                || !sourceName.isBlank()
                || !docSet.isEmpty();
    }

    private static String sanitize(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String trimmedValue = rawValue.trim();
        return trimmedValue.isBlank() ? "" : trimmedValue;
    }

    private static List<String> sanitizeDocSets(List<String> rawDocSets) {
        if (rawDocSets == null || rawDocSets.isEmpty()) {
            return List.of();
        }
        Set<String> retainedDocSets = new HashSet<>();
        List<String> sanitizedDocSets = new ArrayList<>();
        for (String rawDocSet : rawDocSets) {
            String sanitizedDocSet = sanitize(rawDocSet);
            if (!sanitizedDocSet.isBlank() && retainedDocSets.add(sanitizedDocSet)) {
                sanitizedDocSets.add(sanitizedDocSet);
            }
        }
        return sanitizedDocSets;
    }
}
