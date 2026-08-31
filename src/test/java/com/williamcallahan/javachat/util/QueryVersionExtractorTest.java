package com.williamcallahan.javachat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies plural Java release extraction and semantic query boosting. */
class QueryVersionExtractorTest {
    @Test
    void extractsExplicitVersionsInEncounterOrderWithoutDuplicates() {
        assertEquals(
                List.of("24", "21"),
                QueryVersionExtractor.extractVersionNumbers("Compare Java 24 with JDK 21 and Java SE 24"));
    }

    @Test
    void extractsComparisonShorthandAfterAnExplicitVersion() {
        assertEquals(List.of("21", "22"), QueryVersionExtractor.extractVersionNumbers("Java 21/22 List.of"));
        assertEquals(List.of("21", "22"), QueryVersionExtractor.extractVersionNumbers("JDK 21 vs 22 records"));
        assertEquals(List.of("21", "22"), QueryVersionExtractor.extractVersionNumbers("JDK 21 vs. 22 records"));
        assertEquals(List.of("21", "22"), QueryVersionExtractor.extractVersionNumbers("Java 21 + 22 streams"));
        assertEquals(List.of("21", "22", "25"), QueryVersionExtractor.extractVersionNumbers("Java 21/22/25 streams"));
    }

    @Test
    void ignoresUnprefixedNumbersOutsideAComparisonChain() {
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Java 21 with 50 examples"));
        assertEquals(
                List.of("21", "22"),
                QueryVersionExtractor.extractVersionNumbers("Compare Java 21 and 22 and 2 examples"));
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Explain Java 21 and 2 examples"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("Compare 21 and 22 examples"));

        assertEquals(List.of("21", "17"), QueryVersionExtractor.extractVersionNumbers("Java 21 vs 17"));
        assertEquals(List.of("26", "28"), QueryVersionExtractor.extractVersionNumbers("Java 26 vs 28"));
        assertEquals(List.of("100"), QueryVersionExtractor.extractVersionNumbers("Java 100"));
    }

    @Test
    void returnsEmptyVersionsForMissingQueries() {
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers(null));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("  "));
    }

    @Test
    void boostsEveryRequestedRelease() {
        assertEquals(
                "JDK 21 Java SE 21 Java 21 release documentation; "
                        + "JDK 24 Java SE 24 Java 24 release documentation: Compare Java 21 and Java 24",
                QueryVersionExtractor.boostQueryWithVersionContext("Compare Java 21 and Java 24", List.of("21", "24")));
    }
}
