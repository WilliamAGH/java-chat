package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Java API citation mappings project the canonical documentation source manifest.
 */
class DocsSourceRegistryTest {

    @Test
    void returnsImmutableJavaApiDocumentationSourceSnapshot() {
        List<JavaApiDocumentationSource> javaApiDocumentationSources = DocsSourceRegistry.javaApiDocumentationSources();

        assertThrows(UnsupportedOperationException.class, javaApiDocumentationSources::removeFirst);
    }

    @Test
    void returnsImmutableDocumentationSourceSnapshot() {
        List<DocumentationSource> documentationSources = DocsSourceRegistry.documentationSources();

        assertThrows(UnsupportedOperationException.class, documentationSources::removeFirst);
    }

    @Test
    void projectsImmutableOfficialSourceIdentitiesFromBothCanonicalManifests() {
        List<String> expectedSourceIdentities = Stream.concat(
                        DocsSourceRegistry.documentationSources().stream().map(DocumentationSource::docSet),
                        DocsSourceRegistry.javaApiDocumentationSources().stream()
                                .map(JavaApiDocumentationSource::relativeMirrorPath))
                .toList();

        List<String> officialSourceIdentities = DocsSourceRegistry.officialDocumentationSourceIdentities();

        assertEquals(expectedSourceIdentities, officialSourceIdentities);
        assertThrows(UnsupportedOperationException.class, officialSourceIdentities::removeFirst);
    }

    @Test
    void excludesSyntheticNonOfficialSourcesWithoutRestatingCanonicalSourceIdentities() {
        DocumentationSource canonicalOfficialSource =
                DocsSourceRegistry.documentationSources().getFirst();
        JavaApiDocumentationSource canonicalJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        DocumentationSource syntheticNonOfficialSource = mock(DocumentationSource.class);
        when(syntheticNonOfficialSource.sourceKind()).thenReturn("community");
        when(syntheticNonOfficialSource.docSet()).thenReturn("synthetic-community-docs");

        List<String> projectedSourceIdentities = DocsSourceRegistry.projectOfficialDocumentationSourceIdentities(
                List.of(canonicalOfficialSource, syntheticNonOfficialSource), List.of(canonicalJavaApiSource));

        assertEquals(
                List.of(canonicalOfficialSource.docSet(), canonicalJavaApiSource.relativeMirrorPath()),
                projectedSourceIdentities);
        assertFalse(projectedSourceIdentities.contains(syntheticNonOfficialSource.docSet()));
    }

    @Test
    void mapsEveryCanonicalJavaApiMirrorToItsRemoteBaseUrl() {
        List<JavaApiDocumentationSource> javaApiDocumentationSources = DocsSourceRegistry.javaApiDocumentationSources();
        javaApiDocumentationSources.forEach(javaApiDocumentationSource -> {
            String localJavadocFileUrl = "file:///data/docs/" + javaApiDocumentationSource.relativeMirrorPath()
                    + "/api/java.base/java/lang/String.html";
            String expectedOfficialJavadocUrl =
                    javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/lang/String.html";
            assertEquals(expectedOfficialJavadocUrl, DocsSourceRegistry.normalizeDocUrl(localJavadocFileUrl));
        });
        assertEquals(
                javaApiDocumentationSources.size(),
                javaApiDocumentationSources.stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .distinct()
                        .count());
        assertEquals(
                javaApiDocumentationSources.size(),
                javaApiDocumentationSources.stream()
                        .map(JavaApiDocumentationSource::relativeMirrorPath)
                        .distinct()
                        .count());
    }

    @Test
    void mapsEveryCanonicalDocumentationMirrorToItsCitationBaseUrl() {
        List<DocumentationSource> documentationSources = DocsSourceRegistry.documentationSources();
        documentationSources.forEach(documentationSource -> {
            String localDocumentationFileUrl =
                    "file:///data/docs/" + documentationSource.relativeMirrorPath() + "/index.html";
            String expectedOfficialDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
            assertEquals(
                    expectedOfficialDocumentationUrl, DocsSourceRegistry.normalizeDocUrl(localDocumentationFileUrl));
            assertEquals(
                    documentationSource,
                    DocsSourceRegistry.documentationSourceForRelativeMirrorPath(
                                    documentationSource.relativeMirrorPath())
                            .orElseThrow());
            assertEquals(
                    documentationSource,
                    DocsSourceRegistry.documentationSourceForRelativeDocumentPath(
                                    documentationSource.relativeMirrorPath() + "/index.html")
                            .orElseThrow());
        });
        assertEquals(
                documentationSources.size(),
                documentationSources.stream()
                        .map(DocumentationSource::relativeMirrorPath)
                        .distinct()
                        .count());
    }
}
