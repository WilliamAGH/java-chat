package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CLI and shell ingestion project the canonical Java API source inventory.
 */
class JavaApiDocumentationSourceParityTest {

    private static final String LEGACY_JAVA_QUICK_SELECTOR_PREFIX = "java";

    @Test
    void projectsCanonicalJavaApiSourcesIntoCliCatalogAndFetchScript() throws IOException, InterruptedException {
        List<JavaApiDocumentationSource> canonicalJavaApiSources = DocsSourceRegistry.javaApiDocumentationSources();
        List<DocumentationSet> expectedCliDocumentationSets = canonicalJavaApiSources.stream()
                .map(javaApiDocumentationSource -> new DocumentationSet(
                        javaApiDocumentationSource.displayName(), javaApiDocumentationSource.relativeMirrorPath()))
                .toList();
        List<DocumentationSet> actualCliDocumentationSets = DocumentationSetCatalog.allSets().stream()
                .filter(expectedCliDocumentationSets::contains)
                .toList();

        assertEquals(expectedCliDocumentationSets, actualCliDocumentationSets);

        Path fetchScriptPath = Path.of("scripts", "fetch_all_docs.sh").toAbsolutePath();
        ProcessBuilder fetchSourceListingCommand =
                new ProcessBuilder("/bin/bash", fetchScriptPath.toString(), "--list-java-api-sources");
        fetchSourceListingCommand.redirectErrorStream(true);
        Process fetchSourceListingProcess = fetchSourceListingCommand.start();
        String scriptStandardOutput =
                new String(fetchSourceListingProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int scriptExitCode = fetchSourceListingProcess.waitFor();

        assertEquals(0, scriptExitCode, scriptStandardOutput);
        Path sourceManifestPath = Path.of("src", "main", "resources", "java-api-documentation-sources.manifest");
        List<String> manifestSourceRows = Files.readAllLines(sourceManifestPath, StandardCharsets.UTF_8).stream()
                .filter(manifestLine -> !manifestLine.isBlank())
                .skip(1)
                .toList();
        List<String> actualScriptProjections = scriptStandardOutput
                .lines()
                .filter(scriptOutputLine -> !scriptOutputLine.isBlank())
                .toList();
        List<String> actualJavaProjections = canonicalJavaApiSources.stream()
                .map(JavaApiDocumentationSource::toManifestRow)
                .toList();
        assertEquals(manifestSourceRows, actualJavaProjections);
        assertEquals(manifestSourceRows, actualScriptProjections);

        Path fetchProjectionTestPath =
                Path.of("scripts", "test_java_api_fetch_projection.sh").toAbsolutePath();
        ProcessBuilder fetchProjectionTestCommand = new ProcessBuilder("/bin/bash", fetchProjectionTestPath.toString());
        fetchProjectionTestCommand.redirectErrorStream(true);
        Process fetchProjectionTestProcess = fetchProjectionTestCommand.start();
        String fetchProjectionTestOutput =
                new String(fetchProjectionTestProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int fetchProjectionTestExitCode = fetchProjectionTestProcess.waitFor();

        assertEquals(0, fetchProjectionTestExitCode, fetchProjectionTestOutput);
    }

    @Test
    void acceptsOnlyCanonicalRelativeMirrorPathsForManifestBackedJavaApiSets() {
        for (JavaApiDocumentationSource javaApiDocumentationSource : DocsSourceRegistry.javaApiDocumentationSources()) {
            DocumentationSet catalogDocumentationSet = DocumentationSetCatalog.allSets().stream()
                    .filter(documentationSet ->
                            documentationSet.relativePath().equals(javaApiDocumentationSource.relativeMirrorPath()))
                    .findFirst()
                    .orElseThrow();

            String canonicalRelativeMirrorPath = javaApiDocumentationSource.relativeMirrorPath();
            assertEquals(canonicalRelativeMirrorPath, catalogDocumentationSet.primarySelector());
            assertTrue(catalogDocumentationSet.matchesSelectorTokens(Set.of(canonicalRelativeMirrorPath)));

            List<String> prohibitedSelectorAliases = List.of(
                    javaApiDocumentationSource.displayName(),
                    canonicalRelativeMirrorPath.replace('/', '-'),
                    javaApiDocumentationSource.javaRelease(),
                    LEGACY_JAVA_QUICK_SELECTOR_PREFIX + javaApiDocumentationSource.javaRelease(),
                    canonicalRelativeMirrorPath.toUpperCase(Locale.ROOT));
            for (String prohibitedSelectorAlias : prohibitedSelectorAliases) {
                assertFalse(
                        catalogDocumentationSet.matchesSelectorTokens(Set.of(prohibitedSelectorAlias)),
                        "Canonical Java API selector accepted alias " + prohibitedSelectorAlias);
            }
        }
    }

    @Test
    void preservesEstablishedSelectorsForNonJavaDocumentationSets() {
        DocumentationSet quickDocumentationSet =
                DocumentationSetCatalog.quickSets().getFirst();

        assertEquals(quickDocumentationSet.relativePath().replace('/', '-'), quickDocumentationSet.primarySelector());
        assertTrue(quickDocumentationSet.matchesSelectorTokens(
                Set.of(quickDocumentationSet.displayName().toUpperCase(Locale.ROOT))));
    }
}
