package com.williamcallahan.javachat.application.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies Java API method-selector parsing and sparse citation query expansion. */
class JavaApiMethodSelectorTest {

    @Test
    void recognizesPunctuatedTypeMethodInvocationAndAppendsOnlyTheDeclaringType() {
        String citationQuery = "What does Java List.of() return?";

        JavaApiMethodSelector selector =
                JavaApiMethodSelector.fromQuery(citationQuery).orElseThrow();

        assertEquals("", selector.packageName());
        assertEquals("List", selector.typePageName());
        assertEquals("of", selector.methodName());
        assertEquals("List.html", selector.typePageFileName());
        assertEquals("List", selector.sparseQueryTerms());
        assertEquals(citationQuery + " List", JavaApiMethodSelector.expandForSparseCitationQuery(citationQuery));
    }

    @Test
    void recognizesTypeMethodWithoutInvocationParentheses() {
        JavaApiMethodSelector selector =
                JavaApiMethodSelector.fromQuery("Explain Stream.map in Java").orElseThrow();

        assertEquals("", selector.packageName());
        assertEquals("Stream", selector.typePageName());
        assertEquals("map", selector.methodName());
        assertEquals("Stream", selector.sparseQueryTerms());
    }

    @Test
    void retainsPackageAndNestedJavadocTypeSyntaxFromAQualifiedSelector() {
        JavaApiMethodSelector selector = JavaApiMethodSelector.fromQuery(
                        "How does java.util.Map.Entry.comparingByKey work?")
                .orElseThrow();

        assertEquals("java.util", selector.packageName());
        assertEquals("Map.Entry", selector.typePageName());
        assertEquals("comparingByKey", selector.methodName());
        assertEquals("Map.Entry.html", selector.typePageFileName());
        assertEquals("Map.Entry", selector.sparseQueryTerms());
    }

    @Test
    void normalizesWhitespaceWhenConstructedDirectly() {
        JavaApiMethodSelector selector = new JavaApiMethodSelector(" java.util ", " List ", " of ");

        assertEquals("java.util", selector.packageName());
        assertEquals("List", selector.typePageName());
        assertEquals("of", selector.methodName());
        assertEquals("List.html", selector.typePageFileName());
        assertEquals("List", selector.sparseQueryTerms());
        assertTrue(selector.matchesJavadocPath("/java.base/java/util/List.html", null));
    }

    @Test
    void ignoresQualifiedNamesWithoutAnExplicitTypeMethodSelector() {
        assertTrue(
                JavaApiMethodSelector.fromQuery("Read java.util documentation").isEmpty());
    }

    @Test
    void rejectsFilenameShapedPseudoMethods() {
        assertTrue(JavaApiMethodSelector.fromQuery("Read List.java").isEmpty());
        assertTrue(JavaApiMethodSelector.fromQuery("Read String.html").isEmpty());
    }

    @Test
    void requiresCaseSensitiveJavadocTypePageNames() {
        JavaApiMethodSelector selector = new JavaApiMethodSelector("java.util", "List", "of");

        assertFalse(selector.matchesJavadocPath("/java.base/java/util/list.html", null));
    }

    @Test
    void requiresCanonicalCandidatePackageMetadataForUnqualifiedSelectors() {
        JavaApiMethodSelector selector = new JavaApiMethodSelector("", "List", "of");

        assertTrue(selector.matchesJavadocPath("/java.base/java/util/List.html", "java.util"));
        assertFalse(selector.matchesJavadocPath("/java.base/java/util/class-use/List.html", "java.util.class-use"));
        assertFalse(selector.matchesJavadocPath("/List.html", ""));
        assertFalse(selector.matchesJavadocPath("/java.base/java/util/List.html", null));
    }

    @Test
    void matchesQualifiedSelectorsByPathRegardlessOfCandidateMetadata() {
        JavaApiMethodSelector selector = new JavaApiMethodSelector("java.util", "Date", "toString");

        assertTrue(selector.matchesJavadocPath("/java.base/java/util/Date.html", null));
        assertTrue(selector.matchesJavadocPath("/java.base/java/util/Date.html", "java.sql"));
        assertFalse(selector.matchesJavadocPath("/java.sql/java/sql/Date.html", "java.util"));
    }
}
