package com.williamcallahan.javachat.application.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies Java API method-selector parsing and sparse citation query expansion. */
class JavaApiMethodSelectorTest {

    @Test
    void recognizesPunctuatedTypeMethodInvocationAndBuildsMemberSearchTerms() {
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
        assertEquals("List of", selector.sparseQueryTerms());
        assertEquals("List of", JavaApiMethodSelector.sparseCitationQuery(citationQuery));
    }

    @Test
    void recognizesTypeMethodWithoutInvocationParentheses() {
        JavaApiMethodSelector selector =
                JavaApiMethodSelector.fromQuery("Explain Stream.map in Java").orElseThrow();

        assertEquals("", selector.packageName());
        assertEquals("Stream", selector.typePageName());
        assertEquals("map", selector.methodName());
        assertTrue(selector.exactOverloadAnchor().isEmpty());
        assertEquals("Stream map", selector.sparseQueryTerms());
        JavaApiMethodSelector uniqueSelector = JavaApiMethodSelector.uniqueMemberFromQuery("Explain Stream.map in Java")
                .orElseThrow();
        assertEquals(selector.typePageName(), uniqueSelector.typePageName());
        assertEquals(selector.methodName(), uniqueSelector.methodName());
    }

    @Test
    void recognizesMethodReferencesWithoutInventingAnExactInvocationSignature() {
        String citationQuery = "Show an example with java.lang.String::formatted(java.lang.Object...)";

        JavaApiMethodSelector selector =
                JavaApiMethodSelector.uniqueMemberFromQuery(citationQuery).orElseThrow();

        assertEquals("java.lang", selector.packageName());
        assertEquals("String", selector.typePageName());
        assertEquals("formatted", selector.methodName());
        assertEquals("String formatted", selector.sparseQueryTerms());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery(citationQuery)
                .isEmpty());
        assertEquals(
                "formatted",
                JavaApiMethodSelector.uniqueMemberFromQuery("Show String :: formatted")
                        .orElseThrow()
                        .methodName());
        assertTrue(
                JavaApiMethodSelector.uniqueMemberFromQuery("Show String::new").isEmpty());
    }

    @Test
    void isolatesValidatedMemberTermsFromAnswerFormattingProse() {
        String styledExampleQuery = "Show a Java 25 example with inline code String::formatted, a fenced code block, "
                + "and cite official Javadoc.";
        String thirdPartyQuery = "Show a fenced example for SpringApplication.run";

        assertEquals("String formatted", JavaApiMethodSelector.sparseCitationQuery(styledExampleQuery));
        assertEquals(
                thirdPartyQuery + " SpringApplication run", JavaApiMethodSelector.sparseCitationQuery(thirdPartyQuery));
        assertEquals(
                "Compare List.of with Set.of List of",
                JavaApiMethodSelector.sparseCitationQuery("Compare List.of with Set.of"));
        assertEquals("Explain List.add List add", JavaApiMethodSelector.sparseCitationQuery("Explain List.add"));
    }

    @Test
    void rejectsUniqueSelectionWhenAQueryNamesMultipleMembers() {
        assertTrue(JavaApiMethodSelector.uniqueMemberFromQuery("Compare List.of with Set.of")
                .isEmpty());
    }

    @Test
    void classifiesOnlyTypesOwnedByExportedJavaPlatformModules() {
        assertEquals(
                "formatted",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain String.formatted")
                        .orElseThrow()
                        .methodName());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java SpringApplication.run")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java 25 SpringApplication.run")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Javadoc SpringApplication.run")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java 25 SpringApplication.run()")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery(
                        "Explain javax.servlet.http.HttpServlet.service")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain SpringApplication.run")
                .isEmpty());
        assertEquals(
                "formatted",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java 25 String.formatted")
                        .orElseThrow()
                        .methodName());
        assertEquals(
                "of",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain java.util.List.of")
                        .orElseThrow()
                        .methodName());
        assertEquals(
                "of",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java 25 List.of")
                        .orElseThrow()
                        .methodName());
        assertEquals(
                "java.util",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Java 25 List.of")
                        .orElseThrow()
                        .packageName());
        assertEquals(
                "map",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Stream.map in Java")
                        .orElseThrow()
                        .methodName());
        assertEquals(
                "getNodeName",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain org.w3c.dom.Node.getNodeName")
                        .orElseThrow()
                        .methodName());
        assertEquals(
                "java.util",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Map.Entry.comparingByKey")
                        .orElseThrow()
                        .packageName());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain java.util.Map.Missing.foo")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain Map.Missing.foo")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain List.add")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain String.checkIndex")
                .isEmpty());
        assertEquals(
                "java.util",
                JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery("Explain java.util.List.add")
                        .orElseThrow()
                        .packageName());
    }

    @Test
    void rejectsIncompleteAndUnmappedChainedInvocationsForDeterministicMemberSelection() {
        assertTrue(JavaApiMethodSelector.uniqueMemberFromQuery("Explain List.of(E,")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueMemberFromQuery("Explain Javadoc List.of(List<E>)")
                .isEmpty());
        assertEquals(
                "of",
                JavaApiMethodSelector.uniqueMemberFromQuery("Explain List.of(firstValue)")
                        .orElseThrow()
                        .methodName());
        assertTrue(JavaApiMethodSelector.uniqueMemberFromQuery("Explain Stream.of().map(Function)")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueMemberFromQuery("Explain Javadoc Stream.of().map")
                .isEmpty());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery(
                        "Explain Thread.ofVirtual().start(java.lang.Runnable)")
                .isPresent());
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Explain Thread.ofVirtual().start")
                .isEmpty());
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
        assertTrue(JavaApiMethodSelector.uniqueExactOverloadFromQuery("Thread.ofVirtual().start(Run nable)")
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
        assertEquals("Map.Entry comparingByKey", selector.sparseQueryTerms());
    }

    @Test
    void normalizesWhitespaceWhenConstructedDirectly() {
        JavaApiMethodSelector selector = new JavaApiMethodSelector(" java.util ", " List ", " of ");

        assertEquals("java.util", selector.packageName());
        assertEquals("List", selector.typePageName());
        assertEquals("of", selector.methodName());
        assertEquals("List.html", selector.typePageFileName());
        assertEquals("List of", selector.sparseQueryTerms());
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
