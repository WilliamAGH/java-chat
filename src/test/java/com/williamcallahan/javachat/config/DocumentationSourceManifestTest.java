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
                withInsertedLine(canonicalManifestLines, 2, " "),
                withLine(
                        canonicalManifestLines,
                        1,
                        canonicalManifestLines
                                .get(1)
                                .substring(0, canonicalManifestLines.get(1).lastIndexOf('|'))),
                withColumn(canonicalManifestLines, 1, 0, " https://dev.java/learn/"),
                withColumn(canonicalManifestLines, 1, 1, "http://example.invalid/reference/"),
                withColumn(canonicalManifestLines, 1, 1, "https:///reference/"),
                withColumn(canonicalManifestLines, 1, 1, "https://example.invalid/reference"),
                withColumn(canonicalManifestLines, 1, 2, ""),
                withColumn(canonicalManifestLines, 1, 2, "docs/./reference"),
                withColumn(canonicalManifestLines, 1, 2, "docs/../reference"),
                withColumn(canonicalManifestLines, 1, 2, "/docs/reference"),
                withColumn(canonicalManifestLines, 1, 2, "docs\\reference"),
                withColumn(canonicalManifestLines, 1, 4, ""),
                withColumn(canonicalManifestLines, 1, 7, "\u0001"),
                withDuplicateMirrorPath(canonicalManifestLines));

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

    private static List<String> withColumn(
            List<String> manifestLines, int lineIndex, int columnIndex, String replacementColumn) {
        String[] sourceColumns = manifestLines.get(lineIndex).split("\\|", -1);
        sourceColumns[columnIndex] = replacementColumn;
        return withLine(manifestLines, lineIndex, String.join("|", sourceColumns));
    }

    private static List<String> withDuplicateMirrorPath(List<String> manifestLines) {
        String[] duplicateSourceColumns = manifestLines.get(1).split("\\|", -1);
        duplicateSourceColumns[4] = "alternate-doc-set";
        List<String> changedManifestLines = new ArrayList<>(manifestLines);
        changedManifestLines.add(String.join("|", duplicateSourceColumns));
        return List.copyOf(changedManifestLines);
    }

    private record ShellValidation(int exitCode, String standardOutput) {}
}
