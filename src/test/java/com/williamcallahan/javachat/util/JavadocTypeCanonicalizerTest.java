package com.williamcallahan.javachat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies generic-only Javadoc fragments are ignored without anchor-generation failures.
 */
class JavadocTypeCanonicalizerTest {

    @Test
    void canonicalizeTypeReturnsEmptyForGenericOnlyToken() {
        Optional<String> canonicalType = JavadocTypeCanonicalizer.canonicalizeType("<T>", "java.lang", "String");
        assertTrue(canonicalType.isEmpty());
    }

    @Test
    void refineMemberAnchorUrlIgnoresGenericOnlyMethodParameterFragment() {
        String classPageUrl = "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html";
        String surroundingDocText = "Use parse(<T>) to parse values.";

        String refinedUrl =
                JavadocMemberAnchorResolver.refineMemberAnchorUrl(classPageUrl, surroundingDocText, "java.lang");

        assertEquals(classPageUrl, refinedUrl);
    }
}
