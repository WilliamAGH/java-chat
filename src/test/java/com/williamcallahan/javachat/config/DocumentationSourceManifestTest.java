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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that JVM and Bash boundaries accept the same documentation source manifest grammar. */
class DocumentationSourceManifestTest {

    private static final Path CANONICAL_MANIFEST_PATH =
            Path.of("src", "main", "resources", "documentation-sources.manifest");
    private static final Path SHELL_INTERPRETER_PATH = Path.of("scripts", "lib", "documentation_sources.sh");
    private static final Path SEED_DOCUMENT_TYPE_CATALOG_PATH =
            Path.of("src", "main", "resources", "documentation-seed-document-types.manifest");
    private static final Path INVALID_REMOTE_URLS_FIXTURE_PATH =
            Path.of("scripts", "testdata", "documentation_seed", "invalid-remote-urls.txt");

    @TempDir
    Path temporaryDirectory;

    @Test
    void canonicalManifestHeaderOwnsJavaRecordProjection() throws IOException {
        String canonicalManifestHeader = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8)
                .getFirst();
        List<String> canonicalFieldNames = List.of(canonicalManifestHeader.split("\\|", -1));
        List<String> documentationSourceComponentNames = Arrays.stream(DocumentationSource.class.getRecordComponents())
                .map(recordComponent -> recordComponent.getName())
                .toList();

        assertEquals(canonicalFieldNames, DocumentationSourceManifest.canonicalManifestFields());
        assertEquals(canonicalFieldNames, documentationSourceComponentNames);
        assertEquals(canonicalManifestHeader, DocumentationSourceManifest.canonicalManifestHeader());
    }

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
    void rejectsSharedReorderedManifestFixtureInJavaAndBash() throws IOException, InterruptedException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        List<String> reorderedManifestLines = swapFields(canonicalManifestLines, "displayName", "docSet");
        Path reorderedManifestPath = temporaryDirectory.resolve("reordered.manifest");
        Files.write(reorderedManifestPath, reorderedManifestLines, StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> DocumentationSourceManifest.parse(reorderedManifestLines));

        ShellValidation shellValidation = runShellValidation(reorderedManifestPath);
        assertNotEquals(0, shellValidation.exitCode(), shellValidation.standardOutput());
    }

    @Test
    void acceptsSameSourceChildAndSegmentAdjacentLifecycleRootsInJavaAndBash()
            throws IOException, InterruptedException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        String firstActiveMirrorPath = fieldValue(canonicalManifestLines, 1, "relativeMirrorPath");
        List<List<String>> validLifecycleManifestFixtures = List.of(
                withField(
                        canonicalManifestLines,
                        1,
                        "supersededRelativeMirrorPath",
                        firstActiveMirrorPath + "/legacy-release"),
                withField(
                        canonicalManifestLines, 2, "supersededRelativeMirrorPath", firstActiveMirrorPath + "-legacy"));

        for (int fixtureIndex = 0; fixtureIndex < validLifecycleManifestFixtures.size(); fixtureIndex++) {
            List<String> validManifestLines = validLifecycleManifestFixtures.get(fixtureIndex);
            DocumentationSourceManifest.parse(validManifestLines);

            Path validManifestPath = temporaryDirectory.resolve("valid-lifecycle-" + fixtureIndex + ".manifest");
            Files.write(validManifestPath, validManifestLines, StandardCharsets.UTF_8);
            ShellValidation shellValidation = runShellValidation(validManifestPath);
            assertEquals(0, shellValidation.exitCode(), shellValidation.standardOutput());
        }
    }

    @Test
    void rejectsSharedInvalidManifestFixturesInJavaAndBash() throws IOException, InterruptedException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        String supportedSeedDocumentType = Files.readAllLines(SEED_DOCUMENT_TYPE_CATALOG_PATH, StandardCharsets.UTF_8)
                .getFirst();
        List<List<String>> invalidManifestFixtures = new ArrayList<>(List.of(
                withLine(canonicalManifestLines, 0, "invalidHeader"),
                withHeaderField(canonicalManifestLines, "docSet", "unknownField"),
                withHeaderField(canonicalManifestLines, "docSet", "displayName"),
                withoutManifestField(canonicalManifestLines, "docSet"),
                withAppendedManifestField(canonicalManifestLines, "unexpectedField", "unexpected-column"),
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
                withField(canonicalManifestLines, 1, "docSet", "dev-java,alternate"),
                withField(canonicalManifestLines, 1, "docVersion", "\u0001"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "+1"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "01"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "0"),
                withField(canonicalManifestLines, 1, "minimumHtmlFiles", "2147483648"),
                withField(canonicalManifestLines, 1, "rejectRegex", " excluded "),
                withField(canonicalManifestLines, 1, "rejectRegex", "\u0001"),
                withField(canonicalManifestLines, 1, "allowPartial", "TRUE"),
                withField(canonicalManifestLines, 1, "seedDocumentType", supportedSeedDocumentType),
                withField(canonicalManifestLines, 1, "seedDiscoveryUrl", "https://example.invalid/sitemap.xml"),
                withField(canonicalManifestLines, 1, "seedSourcePrefix", "https://example.invalid/reference/"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        supportedSeedDocumentType,
                        "http://example.invalid/sitemap.xml",
                        "https://example.invalid/reference/"),
                withSeedDiscovery(
                        canonicalManifestLines,
                        1,
                        supportedSeedDocumentType,
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
                        " " + supportedSeedDocumentType,
                        "https://example.invalid/reference/index.html",
                        "http://example.invalid/reference/"),
                withField(canonicalManifestLines, 1, "supersededRelativeMirrorPath", "../dev-java"),
                withField(canonicalManifestLines, 1, "supersededRelativeMirrorPath", "dev-java"),
                withSupersededPathContainingOwnActiveMirror(canonicalManifestLines),
                withSupersededPathMatchingAnotherActiveMirror(canonicalManifestLines),
                withSupersededPathContainingAnotherActiveMirror(canonicalManifestLines),
                withSupersededPathWithinAnotherActiveMirror(canonicalManifestLines),
                withDuplicateSupersededMirrorPath(canonicalManifestLines),
                withDuplicateMirrorPath(canonicalManifestLines),
                withDuplicateDocSet(canonicalManifestLines)));
        for (String invalidRemoteUrl : Files.readAllLines(INVALID_REMOTE_URLS_FIXTURE_PATH, StandardCharsets.UTF_8)) {
            invalidManifestFixtures.add(withField(canonicalManifestLines, 1, "fetchUrl", invalidRemoteUrl));
            invalidManifestFixtures.add(withField(canonicalManifestLines, 1, "citationBaseUrl", invalidRemoteUrl));
            invalidManifestFixtures.add(withSeedDiscovery(
                    canonicalManifestLines,
                    1,
                    supportedSeedDocumentType,
                    invalidRemoteUrl,
                    "https://example.invalid/reference/"));
            invalidManifestFixtures.add(withSeedDiscovery(
                    canonicalManifestLines,
                    1,
                    supportedSeedDocumentType,
                    "https://example.invalid/reference/index.html",
                    invalidRemoteUrl));
        }

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

    @Test
    void acceptsSharedRemoteAuthorityBoundariesInJavaAndBash() throws IOException, InterruptedException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        List<String> validCitationBaseUrls = List.of(
                "https://example.invalid:1/reference/",
                "https://example.invalid:65535/reference/",
                "https://[2001:db8::1]/reference/");
        for (int fixtureIndex = 0; fixtureIndex < validCitationBaseUrls.size(); fixtureIndex++) {
            List<String> explicitPortManifestLines =
                    withField(canonicalManifestLines, 1, "citationBaseUrl", validCitationBaseUrls.get(fixtureIndex));

            DocumentationSourceManifest.parse(explicitPortManifestLines);
            Path explicitPortManifestPath = temporaryDirectory.resolve("valid-authority-" + fixtureIndex + ".manifest");
            Files.write(explicitPortManifestPath, explicitPortManifestLines, StandardCharsets.UTF_8);
            ShellValidation shellValidation = runShellValidation(explicitPortManifestPath);
            assertEquals(0, shellValidation.exitCode(), shellValidation.standardOutput());
        }
    }

    @Test
    void rejectsLifecycleRootsOverlappingJavaApiAndLegacyRegistryRoots() throws IOException {
        List<String> canonicalManifestLines = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        for (String externalActiveMirrorAncestor : List.of("java", "oracle")) {
            List<DocumentationSource> documentationSources = DocumentationSourceManifest.parse(
                    withField(canonicalManifestLines, 1, "supersededRelativeMirrorPath", externalActiveMirrorAncestor));
            assertThrows(
                    IllegalStateException.class,
                    () -> DocsSourceRegistry.validateLifecycleMirrorRoots(documentationSources));
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

    private static List<String> swapFields(
            List<String> manifestLines, String firstCanonicalFieldName, String secondCanonicalFieldName) {
        int firstFieldIndex = canonicalFieldIndex(manifestLines, firstCanonicalFieldName);
        int secondFieldIndex = canonicalFieldIndex(manifestLines, secondCanonicalFieldName);
        List<String> reorderedManifestLines = new ArrayList<>();
        for (String manifestLine : manifestLines) {
            String[] manifestFields = manifestLine.split("\\|", -1);
            String firstField = manifestFields[firstFieldIndex];
            manifestFields[firstFieldIndex] = manifestFields[secondFieldIndex];
            manifestFields[secondFieldIndex] = firstField;
            reorderedManifestLines.add(String.join("|", manifestFields));
        }
        return List.copyOf(reorderedManifestLines);
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

    private static List<String> withoutManifestField(List<String> manifestLines, String removedCanonicalFieldName) {
        int removedFieldIndex = canonicalFieldIndex(manifestLines, removedCanonicalFieldName);
        List<String> reducedManifestLines = new ArrayList<>();
        for (String manifestLine : manifestLines) {
            List<String> retainedManifestFields = new ArrayList<>(Arrays.asList(manifestLine.split("\\|", -1)));
            retainedManifestFields.remove(removedFieldIndex);
            reducedManifestLines.add(String.join("|", retainedManifestFields));
        }
        return List.copyOf(reducedManifestLines);
    }

    private static List<String> withAppendedManifestField(
            List<String> manifestLines, String appendedFieldName, String appendedColumnText) {
        List<String> extendedManifestLines = new ArrayList<>();
        extendedManifestLines.add(manifestLines.getFirst() + "|" + appendedFieldName);
        manifestLines.stream()
                .skip(1)
                .map(manifestLine -> manifestLine + "|" + appendedColumnText)
                .forEach(extendedManifestLines::add);
        return List.copyOf(extendedManifestLines);
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

    private static List<String> withSupersededPathMatchingAnotherActiveMirror(List<String> manifestLines) {
        String activeMirrorPath = fieldValue(manifestLines, 1, "relativeMirrorPath");
        return withField(manifestLines, 2, "supersededRelativeMirrorPath", activeMirrorPath);
    }

    private static List<String> withSupersededPathContainingAnotherActiveMirror(List<String> manifestLines) {
        String activeMirrorPath = fieldValue(manifestLines, 4, "relativeMirrorPath");
        String containingMirrorPath = activeMirrorPath.substring(0, activeMirrorPath.indexOf('/'));
        return withField(manifestLines, 1, "supersededRelativeMirrorPath", containingMirrorPath);
    }

    private static List<String> withSupersededPathWithinAnotherActiveMirror(List<String> manifestLines) {
        String activeMirrorPath = fieldValue(manifestLines, 4, "relativeMirrorPath");
        return withField(manifestLines, 1, "supersededRelativeMirrorPath", activeMirrorPath + "/legacy-release");
    }

    private static List<String> withSupersededPathContainingOwnActiveMirror(List<String> manifestLines) {
        String activeMirrorPath = fieldValue(manifestLines, 4, "relativeMirrorPath");
        String containingMirrorPath = activeMirrorPath.substring(0, activeMirrorPath.indexOf('/'));
        return withField(manifestLines, 4, "supersededRelativeMirrorPath", containingMirrorPath);
    }

    private static List<String> withDuplicateSupersededMirrorPath(List<String> manifestLines) {
        List<String> firstSupersededMirrorPath =
                withField(manifestLines, 1, "supersededRelativeMirrorPath", "legacy/shared-reference");
        return withField(firstSupersededMirrorPath, 2, "supersededRelativeMirrorPath", "legacy/shared-reference");
    }

    private static String fieldValue(List<String> manifestLines, int lineIndex, String canonicalFieldName) {
        String[] sourceColumns = manifestLines.get(lineIndex).split("\\|", -1);
        return sourceColumns[canonicalFieldIndex(manifestLines, canonicalFieldName)];
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
