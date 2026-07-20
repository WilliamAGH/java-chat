package com.williamcallahan.javachat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies plural Java release extraction and semantic query boosting. */
class QueryVersionExtractorTest {
    private static final List<String> SUPPORTED_JAVA_RELEASES = List.of("21", "24", "25");

    @Test
    void extractsExplicitVersionsInEncounterOrderWithoutDuplicates() {
        assertEquals(
                List.of("24", "21"),
                QueryVersionExtractor.extractVersionNumbers(
                        "Compare Java 24 with JDK 21 and Java SE 24", SUPPORTED_JAVA_RELEASES));
    }

    @Test
    void extractsComparisonShorthandAfterAnExplicitVersion() {
        assertEquals(
                List.of("21", "24"),
                QueryVersionExtractor.extractVersionNumbers("Java 21/24 List.of", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21", "24"),
                QueryVersionExtractor.extractVersionNumbers("JDK 21 vs 24 records", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21", "24"),
                QueryVersionExtractor.extractVersionNumbers("JDK 21 vs. 24 records", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21", "24"),
                QueryVersionExtractor.extractVersionNumbers("Java 21 + 24 streams", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21", "24", "25"),
                QueryVersionExtractor.extractVersionNumbers("Java 21/24/25 streams", SUPPORTED_JAVA_RELEASES));
    }

    @Test
    void ignoresUnprefixedNumbersOutsideAComparisonChain() {
        assertEquals(
                List.of("21"),
                QueryVersionExtractor.extractVersionNumbers("Java 21 with 50 examples", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21", "24"),
                QueryVersionExtractor.extractVersionNumbers(
                        "Compare Java 21 and 24 and 2 examples", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of("21"),
                QueryVersionExtractor.extractVersionNumbers("Explain Java 21 and 2 examples", SUPPORTED_JAVA_RELEASES));
        assertEquals(
                List.of(),
                QueryVersionExtractor.extractVersionNumbers("Compare 21 and 24 examples", SUPPORTED_JAVA_RELEASES));
    }

    @Test
    void returnsEmptyVersionsForMissingQueries() {
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers(null, SUPPORTED_JAVA_RELEASES));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("  ", SUPPORTED_JAVA_RELEASES));
    }

    @Test
    void boostsEveryRequestedRelease() {
        assertEquals(
                "JDK 21 Java SE 21 Java 21 release documentation; "
                        + "JDK 24 Java SE 24 Java 24 release documentation: Compare Java 21 and Java 24",
                QueryVersionExtractor.boostQueryWithVersionContext("Compare Java 21 and Java 24", List.of("21", "24")));
    }
}
