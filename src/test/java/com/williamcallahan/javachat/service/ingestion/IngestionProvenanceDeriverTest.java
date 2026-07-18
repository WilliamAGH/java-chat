package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.service.ingestion.IngestionProvenanceDeriver.IngestionProvenance;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies that configured official mirrors retain their declared ingestion provenance. */
class IngestionProvenanceDeriverTest {

    private static final String FINGERPRINT_TEST_DOCUMENT_PATH = "gamma";
    private static final String FINGERPRINT_TEST_SOURCE_NAME = "oracle";
    private static final String FINGERPRINT_TEST_SOURCE_KIND = "official";

    @Test
    void usesConfiguredMetadataForEveryDocumentationSource() {
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
    void projectsTheCanonicalJavaApiDocumentTypeForJavaApiMirrors() {
        IngestionProvenanceDeriver provenanceDeriver = new IngestionProvenanceDeriver();
        Path documentationRoot = Path.of("data", "docs").toAbsolutePath().normalize();
        DocsSourceRegistry.JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path javaApiDocument = documentationRoot
                .resolve(javaApiDocumentationSource.relativeMirrorPath())
                .resolve("index.html");

        IngestionProvenance provenance = provenanceDeriver.derive(
                documentationRoot, javaApiDocument, javaApiDocumentationSource.remoteBaseUrl());

        assertEquals(DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE, provenance.docType());
    }

    @Test
    void canonicalFingerprintEncodingSeparatesControlCharactersFromFieldBoundaries() {
        String representedJavaRelease =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().javaRelease();
        IngestionProvenance embeddedSeparatorProvenance =
                javaApiProvenanceForDocumentSet("alpha\u001fbeta", representedJavaRelease);
        IngestionProvenance shiftedSeparatorProvenance =
                javaApiProvenanceForDocumentSet("beta", representedJavaRelease);

        String embeddedSeparatorFingerprintInput = embeddedSeparatorProvenance.fingerprintInput("fingerprint");
        String shiftedSeparatorFingerprintInput = shiftedSeparatorProvenance.fingerprintInput("fingerprint\u001falpha");

        assertNotEquals(embeddedSeparatorFingerprintInput, shiftedSeparatorFingerprintInput);
        assertFalse(embeddedSeparatorFingerprintInput.contains("\u001f"));
        assertEquals(embeddedSeparatorFingerprintInput, embeddedSeparatorProvenance.fingerprintInput("fingerprint"));
    }

    private static IngestionProvenance javaApiProvenanceForDocumentSet(
            String documentationSet, String representedJavaRelease) {
        return new IngestionProvenance(
                documentationSet,
                FINGERPRINT_TEST_DOCUMENT_PATH,
                FINGERPRINT_TEST_SOURCE_NAME,
                FINGERPRINT_TEST_SOURCE_KIND,
                representedJavaRelease,
                DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE);
    }
}
