package com.williamcallahan.javachat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
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
        String classPageUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/lang/String.html";
        String surroundingDocText = "Use parse(<T>) to parse values.";

        String refinedUrl =
                JavadocMemberAnchorResolver.refineMemberAnchorUrl(classPageUrl, surroundingDocText, "java.lang");

        assertEquals(classPageUrl, refinedUrl);
    }
}
