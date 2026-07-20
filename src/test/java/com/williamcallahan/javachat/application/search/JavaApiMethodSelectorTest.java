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
        JavaApiMethodSelector exactSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(citationQuery)
                .orElseThrow();

        assertEquals("", selector.packageName());
        assertEquals("List", selector.typePageName());
        assertEquals("of", selector.methodName());
        assertTrue(selector.exactOverloadAnchor().isEmpty());
        assertEquals("of()", exactSelector.exactOverloadAnchor().orElseThrow());
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
        assertTrue(selector.exactOverloadAnchor().isEmpty());
        assertEquals("Stream", selector.sparseQueryTerms());
    }

    @Test
    void normalizesAnUnambiguousSignatureIntoTheSourceAnchorLookupKey() {
        JavaApiMethodSelector listSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "What does List.of(E, E) return?")
                .orElseThrow();
        JavaApiMethodSelector stringSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "What does String.valueOf(char[]) return?")
                .orElseThrow();
        JavaApiMethodSelector varargsSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "What does List.of(E...) return?")
                .orElseThrow();
        JavaApiMethodSelector threadBuilderSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "Explain java.lang.Thread.Builder.start(java.lang.Runnable)")
                .orElseThrow();

        assertEquals("of(E,E)", listSelector.exactOverloadAnchor().orElseThrow());
        assertEquals("valueOf(char[])", stringSelector.exactOverloadAnchor().orElseThrow());
        assertEquals("of(E...)", varargsSelector.exactOverloadAnchor().orElseThrow());
        assertEquals("java.lang", threadBuilderSelector.packageName());
        assertEquals("Thread.Builder", threadBuilderSelector.typePageName());
        assertEquals(
                "start(java.lang.Runnable)",
                threadBuilderSelector.exactOverloadAnchor().orElseThrow());
    }

    @Test
    void keepsFirstSelectorRelevanceButWithholdsExactKeysForAmbiguousQueries() {
        JavaApiMethodSelector firstSelector = JavaApiMethodSelector.fromQuery("Compare List.of(E, E) with Set.of(E, E)")
                .orElseThrow();

        assertEquals("List", firstSelector.typePageName());
        assertTrue(firstSelector.exactOverloadAnchor().isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("List.of(firstValue, secondValue)")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Compare List.of(E, E) with Set.of(E, E)")
                .isEmpty());
        assertTrue(
                JavaApiMethodSelector.uniqueExactOverloadFromQuery("List.of(E,").isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("List.of(List<E>)")
                .isEmpty());
        JavaApiMethodSelector virtualThreadStartSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "Thread.ofVirtual().start(Runnable)")
                .orElseThrow();
        JavaApiMethodSelector spacedVirtualThreadStartSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "Thread.ofVirtual() . start(Runnable)")
                .orElseThrow();
        JavaApiMethodSelector qualifiedVirtualThreadStartSelector = JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "Thread.ofVirtual()\n.start(java.lang.Runnable)")
                .orElseThrow();
        assertEquals("java.lang", virtualThreadStartSelector.packageName());
        assertEquals("Thread.Builder", virtualThreadStartSelector.typePageName());
        assertEquals(
                "start(java.lang.Runnable)",
                virtualThreadStartSelector.exactOverloadAnchor().orElseThrow());
        assertEquals(
                "start(java.lang.Runnable)",
                spacedVirtualThreadStartSelector.exactOverloadAnchor().orElseThrow());
        assertEquals(
                "start(java.lang.Runnable)",
                qualifiedVirtualThreadStartSelector.exactOverloadAnchor().orElseThrow());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("List.of().stream()")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Stream.of().<String>map(Function)")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Thread.ofVirtual().Start()")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Thread.ofVirtual().")
                .isPresent());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Thread.ofVirtual(). Use the returned builder")
                .isPresent());
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
    void requiresCanonicalCandidatePackageForUnqualifiedSelectors() {
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
