package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    @Test
    void canonicalFingerprintEncodingSeparatesControlCharactersFromFieldBoundaries() {
        IngestionProvenance embeddedSeparatorProvenance =
                new IngestionProvenance("alpha\u001fbeta", "gamma", "oracle", "official", "21", "api-docs");
        IngestionProvenance shiftedSeparatorProvenance =
                new IngestionProvenance("beta", "gamma", "oracle", "official", "21", "api-docs");

        String embeddedSeparatorFingerprintInput = embeddedSeparatorProvenance.fingerprintInput("fingerprint");
        String shiftedSeparatorFingerprintInput = shiftedSeparatorProvenance.fingerprintInput("fingerprint\u001falpha");

        assertNotEquals(embeddedSeparatorFingerprintInput, shiftedSeparatorFingerprintInput);
        assertFalse(embeddedSeparatorFingerprintInput.contains("\u001f"));
        assertEquals(embeddedSeparatorFingerprintInput, embeddedSeparatorProvenance.fingerprintInput("fingerprint"));
    }
}
