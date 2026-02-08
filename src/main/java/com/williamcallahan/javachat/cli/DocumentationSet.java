package com.williamcallahan.javachat.cli;

import java.util.Locale;
import java.util.Set;

/**
 * Identifies a documentation set processed by the CLI ingestion pipeline.
 *
 * <p>The relative path is resolved under the configured docs root (for example, {@code data/docs}).
 * The ID is a stable token used for filtering in {@code DOCS_SETS}.</p>
 *
 * @param displayName user-facing name for logs
 * @param relativePath relative path under the docs root
 */
record DocumentationSet(String displayName, String relativePath) {

    String docSetId() {
        return normalizeToken(relativePath.replace('/', '-'));
    }

    boolean matchesAny(final Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return false;
        }
        final String normalizedName = normalizeToken(displayName);
        final String normalizedPath = normalizeToken(relativePath);
        final String normalizedId = docSetId();
        for (String token : tokens) {
            if (token.equals(normalizedId) || token.equals(normalizedName) || token.equals(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeToken(final String token) {
        if (token == null) {
            return "";
        }
        return token.trim().toLowerCase(Locale.ROOT);
    }
}
