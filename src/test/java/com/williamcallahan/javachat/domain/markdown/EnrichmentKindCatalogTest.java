package com.williamcallahan.javachat.domain.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies strict parsing of the shared enrichment presentation manifest. */
class EnrichmentKindCatalogTest {

    private static final Path CANONICAL_MANIFEST_PATH =
            Path.of("src", "main", "resources", "enrichment-kinds.manifest");

    @Test
    void loadsEveryCanonicalManifestRowInOrder() throws IOException {
        List<String> canonicalRows = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        EnrichmentKindCatalog catalog = EnrichmentKindCatalog.load();

        assertEquals(canonicalRows.size(), catalog.all().size());
        assertEquals(
                canonicalRows.stream()
                        .map(EnrichmentKindCatalogTest::tokenFromManifestRow)
                        .toList(),
                catalog.all().stream()
                        .map(EnrichmentKindCatalog.EnrichmentPresentation::token)
                        .toList());
    }

    @Test
    void acceptsCrLfCanonicalManifestRows() throws IOException {
        String canonicalManifest = Files.readString(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        byte[] crLfManifestBytes = canonicalManifest.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        EnrichmentKindCatalog catalog = EnrichmentKindCatalog.parse(new ByteArrayInputStream(crLfManifestBytes));

        assertEquals(
                Files.readAllLines(CANONICAL_MANIFEST_PATH).size(),
                catalog.all().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidManifestRows")
    void rejectsInvalidCanonicalManifestRows(String invalidCase, List<String> invalidRows) {
        assertThrows(IllegalStateException.class, () -> EnrichmentKindCatalog.parse(invalidRows));
    }

    private static Stream<Arguments> invalidManifestRows() throws IOException {
        List<String> canonicalRows = Files.readAllLines(CANONICAL_MANIFEST_PATH, StandardCharsets.UTF_8);
        String duplicateToken = tokenFromManifestRow(canonicalRows.getFirst());
        String secondRowSuffix =
                canonicalRows.get(1).substring(canonicalRows.get(1).indexOf('|'));

        return Stream.of(
                Arguments.of("rejects an empty manifest", List.of()),
                Arguments.of("rejects blank rows", replaceRow(canonicalRows, 2, " ")),
                Arguments.of("rejects missing columns", replaceRow(canonicalRows, 1, "warning|Warning")),
                Arguments.of(
                        "rejects duplicate tokens", replaceRow(canonicalRows, 1, duplicateToken + secondRowSuffix)),
                Arguments.of(
                        "rejects noncanonical token case",
                        replaceRow(canonicalRows, 0, "Hint" + firstRowSuffix(canonicalRows))),
                Arguments.of("rejects surrounding title whitespace", replaceTitle(canonicalRows, 0, " Helpful Hints")));
    }

    private static List<String> replaceRow(List<String> sourceRows, int rowIndex, String replacementRow) {
        List<String> replacedRows = new ArrayList<>(sourceRows);
        replacedRows.set(rowIndex, replacementRow);
        return List.copyOf(replacedRows);
    }

    private static List<String> replaceTitle(List<String> sourceRows, int rowIndex, String replacementTitle) {
        String sourceRow = sourceRows.get(rowIndex);
        int firstDelimiterIndex = sourceRow.indexOf('|');
        int secondDelimiterIndex = sourceRow.indexOf('|', firstDelimiterIndex + 1);
        return replaceRow(
                sourceRows,
                rowIndex,
                sourceRow.substring(0, firstDelimiterIndex + 1)
                        + replacementTitle
                        + sourceRow.substring(secondDelimiterIndex));
    }

    private static String firstRowSuffix(List<String> sourceRows) {
        String firstRow = sourceRows.getFirst();
        return firstRow.substring(firstRow.indexOf('|'));
    }

    private static String tokenFromManifestRow(String manifestRow) {
        return manifestRow.substring(0, manifestRow.indexOf('|'));
    }
}
