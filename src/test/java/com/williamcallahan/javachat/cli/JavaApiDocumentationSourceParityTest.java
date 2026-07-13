package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CLI and shell ingestion project the canonical Java API source inventory.
 */
class JavaApiDocumentationSourceParityTest {

    @Test
    void projectsCanonicalJavaApiSourcesIntoCliCatalogAndFetchScript() throws IOException, InterruptedException {
        List<JavaApiDocumentationSource> canonicalJavaApiSources = DocsSourceRegistry.javaApiDocumentationSources();
        List<DocumentationSet> expectedCliDocumentationSets = canonicalJavaApiSources.stream()
                .map(javaApiDocumentationSource -> new DocumentationSet(
                        javaApiDocumentationSource.displayName(), javaApiDocumentationSource.relativeMirrorPath()))
                .toList();
        List<DocumentationSet> actualCliDocumentationSets = DocumentationSetCatalog.baseSets().stream()
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
    }
}
