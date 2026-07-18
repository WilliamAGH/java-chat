package com.williamcallahan.javachat.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Loads the manifest-owned document types that select structured discovery behavior. */
final class DocumentationSeedDocumentTypeCatalog {

    private static final String CATALOG_RESOURCE = "/documentation-seed-document-types.manifest";
    private static final Set<String> SEED_DOCUMENT_TYPES = load();

    private DocumentationSeedDocumentTypeCatalog() {}

    static void requireSupported(String seedDocumentType) {
        if (!SEED_DOCUMENT_TYPES.contains(seedDocumentType)) {
            throw new IllegalArgumentException(
                    "seedDocumentType must be declared by the canonical document type catalog");
        }
    }

    private static Set<String> load() {
        InputStream catalogStream = DocumentationSeedDocumentTypeCatalog.class.getResourceAsStream(CATALOG_RESOURCE);
        if (catalogStream == null) {
            throw new IllegalStateException("Canonical documentation seed document type catalog is missing");
        }

        try (BufferedReader catalogReader =
                new BufferedReader(new InputStreamReader(catalogStream, StandardCharsets.UTF_8))) {
            Set<String> seedDocumentTypes = new LinkedHashSet<>();
            int catalogLineNumber = 0;
            String seedDocumentType;
            while ((seedDocumentType = catalogReader.readLine()) != null) {
                catalogLineNumber++;
                try {
                    DocumentationManifestFieldRules.requireCanonicalSeedDocumentType(seedDocumentType);
                } catch (IllegalArgumentException invalidDocumentType) {
                    throw invalidCatalogLine(catalogLineNumber, invalidDocumentType.getMessage());
                }
                if (!seedDocumentTypes.add(seedDocumentType)) {
                    throw invalidCatalogLine(catalogLineNumber, "duplicates " + seedDocumentType);
                }
            }
            if (seedDocumentTypes.isEmpty()) {
                throw new IllegalStateException("Canonical documentation seed document type catalog has no records");
            }
            return Set.copyOf(seedDocumentTypes);
        } catch (IOException catalogReadError) {
            throw new IllegalStateException(
                    "Canonical documentation seed document type catalog could not be read", catalogReadError);
        }
    }

    private static IllegalStateException invalidCatalogLine(int catalogLineNumber, String problem) {
        return new IllegalStateException(
                "Canonical documentation seed document type catalog line " + catalogLineNumber + " " + problem);
    }
}
