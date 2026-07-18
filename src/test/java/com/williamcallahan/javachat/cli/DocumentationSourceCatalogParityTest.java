package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verifies that CLI and shell ingestion project the canonical non-Java source inventory. */
class DocumentationSourceCatalogParityTest {

    @Test
    void executesPythonSeedContractInDefaultGradleTestLane() throws IOException, InterruptedException {
        Path seedContractPath = Path.of("scripts", "test_documentation_seed.py").toAbsolutePath();
        ProcessBuilder seedContractCommand = new ProcessBuilder("python3", seedContractPath.toString());
        seedContractCommand.redirectErrorStream(true);
        Process seedContractProcess = seedContractCommand.start();
        String seedContractStandardOutput =
                new String(seedContractProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int seedContractExitCode = seedContractProcess.waitFor();

        assertEquals(0, seedContractExitCode, seedContractStandardOutput);
    }

    @Test
    void projectsCanonicalDocumentationSourcesIntoCliCatalogAndFetchScript() throws IOException, InterruptedException {
        List<DocumentationSource> canonicalDocumentationSources = DocsSourceRegistry.documentationSources();
        List<DocumentationSet> expectedCliDocumentationSets = canonicalDocumentationSources.stream()
                .map(documentationSource -> new DocumentationSet(
                        documentationSource.displayName(), documentationSource.relativeMirrorPath()))
                .toList();
        List<DocumentationSet> actualCliDocumentationSets = DocumentationSetCatalog.allSets().stream()
                .filter(expectedCliDocumentationSets::contains)
                .toList();

        assertEquals(expectedCliDocumentationSets, actualCliDocumentationSets);

        Path fetchScriptPath = Path.of("scripts", "fetch_all_docs.sh").toAbsolutePath();
        ProcessBuilder fetchSourceListingCommand =
                new ProcessBuilder("/bin/bash", fetchScriptPath.toString(), "--list-documentation-sources");
        fetchSourceListingCommand.redirectErrorStream(true);
        Process fetchSourceListingProcess = fetchSourceListingCommand.start();
        String scriptStandardOutput =
                new String(fetchSourceListingProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int scriptExitCode = fetchSourceListingProcess.waitFor();

        assertEquals(0, scriptExitCode, scriptStandardOutput);
        Path sourceManifestPath = Path.of("src", "main", "resources", "documentation-sources.manifest");
        List<String> manifestSourceRows = Files.readAllLines(sourceManifestPath, StandardCharsets.UTF_8).stream()
                .filter(manifestLine -> !manifestLine.isBlank())
                .skip(1)
                .toList();
        List<String> actualScriptProjections = scriptStandardOutput
                .lines()
                .filter(scriptOutputLine -> !scriptOutputLine.isBlank())
                .toList();
        List<String> actualJavaProjections = canonicalDocumentationSources.stream()
                .map(DocumentationSource::toManifestRow)
                .toList();
        assertEquals(manifestSourceRows, actualJavaProjections);
        assertEquals(manifestSourceRows, actualScriptProjections);

        Path fetchProjectionTestPath =
                Path.of("scripts", "test_documentation_fetch_projection.sh").toAbsolutePath();
        ProcessBuilder fetchProjectionTestCommand = new ProcessBuilder("/bin/bash", fetchProjectionTestPath.toString());
        fetchProjectionTestCommand.redirectErrorStream(true);
        Process fetchProjectionTestProcess = fetchProjectionTestCommand.start();
        String fetchProjectionTestOutput =
                new String(fetchProjectionTestProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int fetchProjectionTestExitCode = fetchProjectionTestProcess.waitFor();

        assertEquals(0, fetchProjectionTestExitCode, fetchProjectionTestOutput);
    }

    @Test
    void acceptsOnlyCanonicalRelativeMirrorPathsForManifestBackedDocumentationSets() {
        for (DocumentationSource documentationSource : DocsSourceRegistry.documentationSources()) {
            DocumentationSet catalogDocumentationSet = DocumentationSetCatalog.allSets().stream()
                    .filter(documentationSet ->
                            documentationSet.relativePath().equals(documentationSource.relativeMirrorPath()))
                    .findFirst()
                    .orElseThrow();

            String canonicalRelativeMirrorPath = documentationSource.relativeMirrorPath();
            assertEquals(canonicalRelativeMirrorPath, catalogDocumentationSet.primarySelector());
            assertTrue(catalogDocumentationSet.matchesSelectorTokens(Set.of(canonicalRelativeMirrorPath)));

            List<String> prohibitedSelectorAliases = Stream.of(
                            documentationSource.displayName(),
                            canonicalRelativeMirrorPath.replace('/', '-'),
                            canonicalRelativeMirrorPath.toUpperCase(Locale.ROOT))
                    .filter(selectorAlias -> !selectorAlias.equals(canonicalRelativeMirrorPath))
                    .toList();
            for (String prohibitedSelectorAlias : prohibitedSelectorAliases) {
                assertFalse(
                        catalogDocumentationSet.matchesSelectorTokens(Set.of(prohibitedSelectorAlias)),
                        "Canonical documentation selector accepted alias " + prohibitedSelectorAlias);
            }
        }
    }
}
