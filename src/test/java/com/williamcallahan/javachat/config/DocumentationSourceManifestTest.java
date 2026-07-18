package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that JVM and Bash boundaries accept the same documentation source manifest grammar. */
class DocumentationSourceManifestTest {

    private static final Path CANONICAL_MANIFEST_PATH =
            Path.of("src", "main", "resources", "documentation-sources.manifest");
    private static final Path SHELL_INTERPRETER_PATH = Path.of("scripts", "lib", "documentation_sources.sh");

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsSharedCrLfManifestFixtureInJavaAndBash() throws IOException, InterruptedException {
        String canonicalManifestText = Files.readString(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        String crLfManifestText = canonicalManifestText.replace("\n", "\r\n");
        Path crLfManifestPath = temporaryDirectory.resolve("crlf.manifest");
        Files.writeString(crLfManifestPath, crLfManifestText, StandardCharsets.UTF_8);

        try (InputStream manifestStream = Files.newInputStream(crLfManifestPath)) {
            List<String> javaProjectionRows = DocumentationSourceManifest.parse(manifestStream).stream()
                    .map(DocumentationSource::toManifestRow)
                    .toList();
            List<String> canonicalRows = canonicalManifestText.lines().skip(1).toList();
            assertEquals(canonicalRows, javaProjectionRows);
        }

        ShellValidation shellValidation = runShellValidation(crLfManifestPath);
        assertEquals(0, shellValidation.exitCode(), shellValidation.standardOutput());
    }

    @Test
    void rejectsSharedInvalidManifestFixturesInJavaAndBash() throws IOException, InterruptedException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        List<List<String>> invalidManifestFixtures = List.of(
                withLine(canonicalManifestLines, 0, "invalidHeader"),
                withHeaderField(canonicalManifestLines, "docSet", "unknownField"),
                withInsertedLine(canonicalManifestLines, 2, " "),
                withLine(
                        canonicalManifestLines,
                        1,
                        canonicalManifestLines
                                .get(1)
                                .substring(0, canonicalManifestLines.get(1).lastIndexOf('|'))),
                withField(canonicalManifestLines, 1, "fetchUrl", " https://dev.java/learn/"),
                withField(canonicalManifestLines, 1, "citationBaseUrl", "http://example.invalid/reference/"),
                withField(canonicalManifestLines, 1, "citationBaseUrl", "https:///reference/"),
                withField(canonicalManifestLines, 1, "citationBaseUrl", "https://example.invalid/reference"),
                withField(canonicalManifestLines, 1, "relativeMirrorPath", ""),
                withField(canonicalManifestLines, 1, "relativeMirrorPath", "docs/./reference"),
                withField(canonicalManifestLines, 1, "relativeMirrorPath", "docs/../reference"),
                withField(canonicalManifestLines, 1, "relativeMirrorPath", "/docs/reference"),
                withField(canonicalManifestLines, 1, "relativeMirrorPath", "docs\\reference"),
                withField(canonicalManifestLines, 1, "docSet", ""),
                withField(canonicalManifestLines, 1, "docVersion", "\u0001"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "+1"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "01"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "0"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "2147483648"),
                withField(canonicalManifestLines, 1, "rejectRegex", " excluded "),
                withField(canonicalManifestLines, 1, "rejectRegex", "\u0001"),
                withField(canonicalManifestLines, 1, "allowPartial", "TRUE"),
                withField(canonicalManifestLines, 1, "seedDocumentType", "xml-sitemap"),
                withField(canonicalManifestLines, 1, "seedDiscoveryUrl", "https://example.invalid/sitemap.xml"),
                withField(canonicalManifestLines, 1, "seedSourcePrefix", "https://example.invalid/reference/"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        "xml-sitemap",
                        "http://example.invalid/sitemap.xml",
                        "https://example.invalid/reference/"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        "html-links",
                        "https://example.invalid/reference/index.html",
                        "http://example.invalid/reference"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        "unsupported-discovery",
                        "https://example.invalid/reference/index.html",
                        "https://example.invalid/reference/"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        " html-links",
                        "https://example.invalid/reference/index.html",
                        "http://example.invalid/reference/"),
                withDuplicateMirrorPath(canonicalManifestLines),
                withDuplicateDocSet(canonicalManifestLines));

        for (int fixtureIndex = 0; fixtureIndex < invalidManifestFixtures.size(); fixtureIndex++) {
            List<String> invalidManifestLines = invalidManifestFixtures.get(fixtureIndex);
            assertThrows(
                    IllegalStateException.class,
                    () -> DocumentationSourceManifest.parse(invalidManifestLines),
                    "Java accepted invalid fixture " + fixtureIndex);

            Path invalidManifestPath = temporaryDirectory.resolve("invalid-manifest-" + fixtureIndex + ".manifest");
            Files.write(invalidManifestPath, invalidManifestLines, StandardCharsets.UTF_8);
            ShellValidation shellValidation = runShellValidation(invalidManifestPath);
            assertNotEquals(
                    0,
                    shellValidation.exitCode(),
                    "Bash accepted invalid fixture " + fixtureIndex + ": " + shellValidation.standardOutput());
        }
    }

    private static ShellValidation runShellValidation(Path manifestPath) throws IOException, InterruptedException {
        Process bashValidation = new ProcessBuilder(
                        "/bin/bash",
                        "-c",
                        "source \"$1\"; load_documentation_sources \"$2\"",
                        "manifest-validation",
                        SHELL_INTERPRETER_PATH.toAbsolutePath().toString(),
                        manifestPath.toString())
                .redirectErrorStream(true)
                .start();
        String standardOutput = new String(bashValidation.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = bashValidation.waitFor();
        return new ShellValidation(exitCode, standardOutput);
    }

    private static List<String> withLine(List<String> manifestLines, int lineIndex, String replacementLine) {
        List<String> changedManifestLines = new ArrayList<>(manifestLines);
        changedManifestLines.set(lineIndex, replacementLine);
        return List.copyOf(changedManifestLines);
    }

    private static List<String> withInsertedLine(List<String> manifestLines, int lineIndex, String insertedLine) {
        List<String> changedManifestLines = new ArrayList<>(manifestLines);
        changedManifestLines.add(lineIndex, insertedLine);
        return List.copyOf(changedManifestLines);
    }

    private static List<String> withField(
            List<String> manifestLines, int lineIndex, String canonicalFieldName, String replacementField) {
        String[] sourceColumns = manifestLines.get(lineIndex).split("\\|", -1);
        sourceColumns[canonicalFieldIndex(manifestLines, canonicalFieldName)] = replacementField;
        return withLine(manifestLines, lineIndex, String.join("|", sourceColumns));
    }

    private static List<String> withHeaderField(
            List<String> manifestLines, String canonicalFieldName, String replacementField) {
        String[] manifestHeaderFields = manifestLines.getFirst().split("\\|", -1);
        manifestHeaderFields[canonicalFieldIndex(manifestLines, canonicalFieldName)] = replacementField;
        return withLine(manifestLines, 0, String.join("|", manifestHeaderFields));
    }

    private static int canonicalFieldIndex(List<String> manifestLines, String canonicalFieldName) {
        String[] manifestHeaderFields = manifestLines.getFirst().split("\\|", -1);
        for (int fieldIndex = 0; fieldIndex < manifestHeaderFields.length; fieldIndex++) {
            if (manifestHeaderFields[fieldIndex].equals(canonicalFieldName)) {
                return fieldIndex;
            }
        }
        throw new IllegalArgumentException(
                "Canonical documentation source manifest has no field " + canonicalFieldName);
    }

    private static List<String> withDuplicateMirrorPath(List<String> manifestLines) {
        List<String> manifestLinesWithAlternateDocSet = withField(manifestLines, 1, "docSet", "alternate-doc-set");
        return withInsertedLine(
                manifestLinesWithAlternateDocSet,
                manifestLinesWithAlternateDocSet.size(),
                manifestLinesWithAlternateDocSet.get(1));
    }

    private static List<String> withSeedDiscovery(
            List<String> manifestLines,
            int lineIndex,
            String seedDocumentType,
            String seedDiscoveryUrl,
            String seedSourcePrefix) {
        List<String> manifestLinesWithDocumentType =
                withField(manifestLines, lineIndex, "seedDocumentType", seedDocumentType);
        List<String> manifestLinesWithDiscoveryUrl =
                withField(manifestLinesWithDocumentType, lineIndex, "seedDiscoveryUrl", seedDiscoveryUrl);
        return withField(manifestLinesWithDiscoveryUrl, lineIndex, "seedSourcePrefix", seedSourcePrefix);
    }

    private static List<String> withDuplicateDocSet(List<String> manifestLines) {
        List<String> manifestLinesWithAlternateMirrorPath =
                withField(manifestLines, 1, "relativeMirrorPath", "alternate/mirror-path");
        return withInsertedLine(
                manifestLinesWithAlternateMirrorPath,
                manifestLinesWithAlternateMirrorPath.size(),
                manifestLinesWithAlternateMirrorPath.get(1));
    }

    private record ShellValidation(int exitCode, String standardOutput) {}
}
