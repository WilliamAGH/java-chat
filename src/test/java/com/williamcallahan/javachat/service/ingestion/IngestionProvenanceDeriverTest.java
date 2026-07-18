package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.service.ingestion.IngestionProvenanceDeriver.IngestionProvenance;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies that manifest-governed mirrors retain their declared ingestion provenance. */
class IngestionProvenanceDeriverTest {

    @Test
    void usesCanonicalManifestMetadataForEveryDocumentationSource() {
        IngestionProvenanceDeriver provenanceDeriver = new IngestionProvenanceDeriver();
        Path documentationRoot = Path.of("data", "docs").toAbsolutePath().normalize();

        for (DocumentationSource documentationSource : DocsSourceRegistry.documentationSources()) {
            Path sourceRoot = documentationRoot.resolve(documentationSource.relativeMirrorPath());
            Path documentFile = sourceRoot.resolve("index.html");

            IngestionProvenance provenance = provenanceDeriver.derive(
                    documentationRoot, documentFile, documentationSource.citationBaseUrl() + "index.html");

            assertEquals(documentationSource.docSet(), provenance.docSet());
            assertEquals("index.html", provenance.docPath());
            assertEquals(documentationSource.docSet(), provenance.sourceName());
            assertEquals(documentationSource.sourceKind(), provenance.sourceKind());
            assertEquals(documentationSource.docVersion(), provenance.docVersion());
            assertEquals(documentationSource.docType(), provenance.docType());
        }
    }
}
