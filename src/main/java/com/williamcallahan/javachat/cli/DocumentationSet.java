package com.williamcallahan.javachat.cli;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Identifies a documentation set processed by the CLI ingestion pipeline.
 *
 * <p>The relative path is resolved under the configured docs root (for example, {@code data/docs}).
 * Manifest-backed sets accept only their canonical relative mirror path; other sets retain their
 * established filter tokens.</p>
 *
 * @param displayName user-facing name for logs
 * @param relativePath relative path under the docs root
 */
record DocumentationSet(String displayName, String relativePath) {

    private static final Set<String> MANIFEST_RELATIVE_MIRROR_PATHS = Stream.concat(
                    DocsSourceRegistry.javaApiDocumentationSources().stream()
                            .map(javaApiDocumentationSource -> javaApiDocumentationSource.relativeMirrorPath()),
                    DocsSourceRegistry.documentationSources().stream()
                            .map(documentationSource -> documentationSource.relativeMirrorPath()))
            .collect(Collectors.toUnmodifiableSet());

    String primarySelector() {
        if (isManifestBackedDocumentationSet()) {
            return relativePath;
        }
        return normalizeToken(relativePath.replace('/', '-'));
    }

    boolean matchesSelectorTokens(final Set<String> selectorTokens) {
        if (selectorTokens == null || selectorTokens.isEmpty()) {
            return false;
        }
        if (isManifestBackedDocumentationSet()) {
            return selectorTokens.contains(relativePath);
        }
        final String normalizedName = normalizeToken(displayName);
        final String normalizedPath = normalizeToken(relativePath);
        final String normalizedId = primarySelector();
        for (String selectorToken : selectorTokens) {
            final String normalizedSelectorToken = normalizeToken(selectorToken);
            if (normalizedSelectorToken.equals(normalizedId)
                    || normalizedSelectorToken.equals(normalizedName)
                    || normalizedSelectorToken.equals(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean isManifestBackedDocumentationSet() {
        return MANIFEST_RELATIVE_MIRROR_PATHS.contains(relativePath);
    }

    private static String normalizeToken(final String selectorToken) {
        if (selectorToken == null) {
            return "";
        }
        return selectorToken.trim().toLowerCase(Locale.ROOT);
    }
}
