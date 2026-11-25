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
    void rejectsExplicitQuantityPhrasesWithoutSuppressingReleaseRequests() {
        for (String quantityNoun : List.of(
                "day", "days", "hour", "hours", "minute", "minutes", "second", "seconds", "times", "line", "lines",
                "week", "weeks", "month", "months", "year", "years")) {
            assertEquals(
                    List.of(),
                    QueryVersionExtractor.extractVersionNumbers("Java 100 " + quantityNoun + " of practice"));
        }
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("JDK-30-HOURS of video"));
        assertEquals(List.of("5"), QueryVersionExtractor.extractVersionNumbers("Java 5"));
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Java 21 time API"));
        assertEquals(List.of("25"), QueryVersionExtractor.extractVersionNumbers("Java 25 examples"));
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Java 100 days of code in Java 21"));
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Java 100 months of code in Java 21"));
    }

    @Test
    void rejectsTemporalQuantityPhrasingsWhereJavaPrecedesTheDigits() {
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("switched to Java 6 months ago"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("I have Java 5 years of experience"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("Java 5 years experience"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("been on Java 8 months"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("learned Java 4 weeks ago"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("ran Java 11 hours straight"));
        assertEquals(List.of(), QueryVersionExtractor.extractVersionNumbers("practiced Java 3 years on the side"));
    }

    @Test
    void extractsReleaseRequestsAdjacentToNonQuantitySuffixWords() {
        assertEquals(List.of("25"), QueryVersionExtractor.extractVersionNumbers("Java 25 monthly cadence"));
        assertEquals(List.of("25"), QueryVersionExtractor.extractVersionNumbers("Java 25 yearly release schedule"));
        assertEquals(List.of("25"), QueryVersionExtractor.extractVersionNumbers("Java 25 weekly digest"));
        assertEquals(List.of("21"), QueryVersionExtractor.extractVersionNumbers("Java 21 API"));
        assertEquals(
                List.of("25", "21"), QueryVersionExtractor.extractVersionNumbers("Java 25 examples and Java 21 notes"));
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
