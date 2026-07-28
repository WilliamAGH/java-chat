package com.williamcallahan.javachat.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes optional metadata constraints pushed into Qdrant queries.
 *
 * @param docVersions document version tokens matched with any-of semantics, or empty
 * @param sourceKind source kind token, or empty
 * @param docType document type token, or empty
 * @param sourceName source name token, or empty
 * @param docSet documentation-set tokens matched with any-of semantics, or empty
 */
public record RetrievalConstraint(
        List<String> docVersions, String sourceKind, String docType, String sourceName, List<String> docSet) {

    /** Creates a normalized immutable retrieval constraint. */
    public RetrievalConstraint {
        docVersions = List.copyOf(sanitizeTokens(docVersions));
        sourceKind = sanitize(sourceKind);
        docType = sanitize(docType);
        sourceName = sanitize(sourceName);
        docSet = List.copyOf(sanitizeTokens(docSet));
    }

    /** Returns an unconstrained retrieval constraint. */
    public static RetrievalConstraint none() {
        return new RetrievalConstraint(List.of(), "", "", "", List.of());
    }

    /** Returns a constraint limited to the supplied document versions. */
    public static RetrievalConstraint forDocVersions(List<String> docVersions) {
        return new RetrievalConstraint(docVersions, "", "", "", List.of());
    }

    /** Returns a constraint limited to official documentation sets. */
    public static RetrievalConstraint forOfficialDocSets(List<String> docSet) {
        RetrievalConstraint officialDocSetConstraint = new RetrievalConstraint(List.of(), "official", "", "", docSet);
        if (officialDocSetConstraint.docSet().isEmpty()) {
            throw new IllegalArgumentException("Official docSet constraint cannot be empty");
        }
        return officialDocSetConstraint;
    }

    /**
     * Intersects this constraint with required document versions.
     *
     * @throws IllegalArgumentException when existing and required versions do not overlap
     */
    public RetrievalConstraint withDocVersions(List<String> requiredDocumentVersions) {
        List<String> sanitizedRequiredVersions = sanitizeTokens(requiredDocumentVersions);
        if (sanitizedRequiredVersions.isEmpty()) {
            return this;
        }
        if (docVersions.isEmpty()) {
            return new RetrievalConstraint(sanitizedRequiredVersions, sourceKind, docType, sourceName, docSet);
        }
        List<String> intersectedVersions =
                sanitizedRequiredVersions.stream().filter(docVersions::contains).toList();
        if (intersectedVersions.isEmpty()) {
            throw new IllegalArgumentException("Conflicting document version constraints");
        }
        if (docVersions.equals(intersectedVersions)) {
            return this;
        }
        return new RetrievalConstraint(intersectedVersions, sourceKind, docType, sourceName, docSet);
    }

    /**
     * Replaces the documentation-set alternatives while preserving every other server-side filter.
     *
     * <p>Unlike {@link #withDocVersions(List)}, this deliberately does not intersect the existing
     * documentation-set alternatives. Callers use it when one broad source scope has a newer
     * canonical replacement.</p>
     *
     * @param replacementDocSets replacement documentation-set tokens matched with any-of semantics
     * @return this constraint with the replacement documentation-set scope
     */
    public RetrievalConstraint withDocSetScope(List<String> replacementDocSets) {
        List<String> sanitizedReplacementDocSets = sanitizeTokens(replacementDocSets);
        if (docSet.equals(sanitizedReplacementDocSets)) {
            return this;
        }
        return new RetrievalConstraint(docVersions, sourceKind, docType, sourceName, sanitizedReplacementDocSets);
    }

    /** Returns true when at least one server-side filter can be applied. */
    public boolean hasServerSideConstraint() {
        return !docVersions.isEmpty()
                || !sourceKind.isBlank()
                || !docType.isBlank()
                || !sourceName.isBlank()
                || !docSet.isEmpty();
    }

    private static String sanitize(String rawToken) {
        if (rawToken == null) {
            return "";
        }
        String trimmedToken = rawToken.trim();
        return trimmedToken.isBlank() ? "" : trimmedToken;
    }

    private static List<String> sanitizeTokens(List<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return List.of();
        }
        Set<String> retainedTokens = new HashSet<>();
        List<String> sanitizedTokens = new ArrayList<>();
        for (String rawToken : rawTokens) {
            String sanitizedToken = sanitize(rawToken);
            if (!sanitizedToken.isBlank() && retainedTokens.add(sanitizedToken)) {
                sanitizedTokens.add(sanitizedToken);
            }
        }
        return sanitizedTokens;
    }
}
