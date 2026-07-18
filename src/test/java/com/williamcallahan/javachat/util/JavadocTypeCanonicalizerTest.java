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

    @Test
    void refineMemberAnchorUrlDoesNotTreatInvocationArgumentsAsDeclarationTypes() {
        String classPageUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/lang/Record.html";
        String surroundingDocText = "return equals(this.SomeField, r.OTHER_FIELD);";

        String refinedUrl =
                JavadocMemberAnchorResolver.refineMemberAnchorUrl(classPageUrl, surroundingDocText, "java.lang");

        assertEquals(classPageUrl, refinedUrl);
    }

    @Test
    void canonicalizeTypePreservesQualifiedJavadocDeclarationTypes() {
        Optional<String> canonicalType =
                JavadocTypeCanonicalizer.canonicalizeType("java.lang.Object", "java.lang", "Record");

        assertEquals(Optional.of("java.lang.Object"), canonicalType);
    }

    @Test
    void refineMemberAnchorUrlPreservesQualifiedDeclarationParameterTypes() {
        String classPageUrl =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst().remoteBaseUrl()
                        + "java.base/java/lang/Record.html";
        String surroundingDocText = "public boolean equals(java.lang.Object comparisonTarget)";

        String refinedUrl =
                JavadocMemberAnchorResolver.refineMemberAnchorUrl(classPageUrl, surroundingDocText, "java.lang");

        assertEquals(classPageUrl + "#equals(java.lang.Object)", refinedUrl);
    }
}
