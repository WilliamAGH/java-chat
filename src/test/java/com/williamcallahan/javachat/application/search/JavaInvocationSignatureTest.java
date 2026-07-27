package com.williamcallahan.javachat.application.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies conservative query-signature parsing for exact Javadoc anchor lookup. */
class JavaInvocationSignatureTest {

    private static final String LIST_OF_SELECTOR = "List.of";

    @Test
    void leavesTheSignatureUnavailableWithoutInvocationParentheses() {
        JavaInvocationSignature invocationSignature =
                JavaInvocationSignature.afterMethodName(LIST_OF_SELECTOR, LIST_OF_SELECTOR.length());

        assertTrue(invocationSignature.anchorFor("of").isEmpty());
    }

    @Test
    void normalizesTypeOnlyParametersArraysAndVarargs() {
        String queryText = LIST_OF_SELECTOR + "( E , java . lang . String [ ] , T ... )";

        JavaInvocationSignature invocationSignature =
                JavaInvocationSignature.afterMethodName(queryText, LIST_OF_SELECTOR.length());

        assertEquals(
                "of(E,java.lang.String[],T...)",
                invocationSignature.anchorFor("of").orElseThrow());
    }

    @Test
    void refusesValuesNestedCallsGenericsAndIncompleteSyntax() {
        assertUnavailable("List.of(firstValue, secondValue)");
        assertUnavailable("List.of(factory.create(E))");
        assertUnavailable("List.of(List<E>)");
        assertUnavailable("List.of(String, String)");
        assertUnavailable("List.of(Map.Entry)");
        assertUnavailable("List.of(java.time.Month.JANUARY)");
        assertUnavailable("List.of(E,");
    }

    private static void assertUnavailable(String queryText) {
        JavaInvocationSignature invocationSignature =
                JavaInvocationSignature.afterMethodName(queryText, LIST_OF_SELECTOR.length());

        assertTrue(invocationSignature.anchorFor("of").isEmpty());
    }
}
