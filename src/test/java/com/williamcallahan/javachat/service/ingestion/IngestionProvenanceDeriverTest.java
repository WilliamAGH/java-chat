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
    void usesCanonicalJavaApiProvenanceWhenIngestionStartsBelowMirrorRoot() {
        IngestionProvenanceDeriver provenanceDeriver = new IngestionProvenanceDeriver();
        Path documentationRoot = Path.of("data", "docs").toAbsolutePath().normalize();

        for (DocsSourceRegistry.JavaApiDocumentationSource javaApiDocumentationSource :
                DocsSourceRegistry.javaApiDocumentationSources()) {
            Path targetedIngestionRoot = documentationRoot
                    .resolve(javaApiDocumentationSource.relativeMirrorPath())
                    .resolve("api/java.base/java/util");
            Path javaApiDocument = targetedIngestionRoot.resolve("List.html");

            IngestionProvenance provenance = provenanceDeriver.derive(
                    targetedIngestionRoot,
                    javaApiDocument,
                    javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/util/List.html");

            assertEquals(javaApiDocumentationSource.relativeMirrorPath(), provenance.docSet());
            assertEquals("api/java.base/java/util/List.html", provenance.docPath());
            assertEquals("oracle", provenance.sourceName());
            assertEquals("official", provenance.sourceKind());
            assertEquals(javaApiDocumentationSource.javaRelease(), provenance.docVersion());
            assertEquals(DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE, provenance.docType());
        }
    }

    @Test
    void usesSelectedBoundaryRootForUnregisteredDocumentationSets() {
        IngestionProvenanceDeriver provenanceDeriver = new IngestionProvenanceDeriver();
        Path selectedBooksRoot =
                Path.of("arbitrary", "mirror", "books").toAbsolutePath().normalize();
        Path selectedBook = selectedBooksRoot.resolve("ThinkJava.pdf");

        IngestionProvenance provenance =
                provenanceDeriver.derive(selectedBooksRoot, selectedBook, "https://javachat.ai/pdfs/ThinkJava.pdf");

        assertEquals("books", provenance.docSet());
        assertEquals("ThinkJava.pdf", provenance.docPath());
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
