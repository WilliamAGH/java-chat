package com.williamcallahan.javachat.service;

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
 */
public record RetrievalConstraint(String docVersion, String sourceKind, String docType, String sourceName) {

    /**
     * Creates a normalized retrieval constraint.
     */
    public RetrievalConstraint {
        docVersion = sanitize(docVersion);
        sourceKind = sanitize(sourceKind);
        docType = sanitize(docType);
        sourceName = sanitize(sourceName);
    }

    /**
     * Returns an unconstrained retrieval constraint.
     *
     * @return unconstrained retrieval constraint
     */
    public static RetrievalConstraint none() {
        return new RetrievalConstraint("", "", "", "");
    }

    /**
     * Returns a version-only retrieval constraint.
     *
     * @param docVersion document version token
     * @return retrieval constraint containing only doc version
     */
    public static RetrievalConstraint forDocVersion(String docVersion) {
        return new RetrievalConstraint(docVersion, "", "", "");
    }

    /**
     * Returns true when at least one server-side filter can be applied.
     *
     * @return true when the constraint has one or more non-empty fields
     */
    public boolean hasServerSideConstraint() {
        return !docVersion.isBlank() || !sourceKind.isBlank() || !docType.isBlank() || !sourceName.isBlank();
    }

    private static String sanitize(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        String trimmedValue = rawValue.trim();
        return trimmedValue.isBlank() ? "" : trimmedValue;
    }
}
