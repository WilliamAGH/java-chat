package com.williamcallahan.javachat.domain.javaapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies exact Javadoc member DOM identifiers are validated once by their domain owner. */
class JavadocMemberAnchorTest {

    @Test
    void preservesExactDomIdentifier() {
        String domIdentifier = "map(java.util.function.Function)";

        JavadocMemberAnchor memberAnchor = new JavadocMemberAnchor(domIdentifier);

        assertEquals(domIdentifier, memberAnchor.domIdentifier());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " map()", "map() ", "#map()", "map()#details"})
    void rejectsNonExactDomIdentifier(String invalidDomIdentifier) {
        assertThrows(IllegalArgumentException.class, () -> new JavadocMemberAnchor(invalidDomIdentifier));
    }

    @Test
    void rejectsNullDomIdentifier() {
        assertThrows(NullPointerException.class, () -> new JavadocMemberAnchor(null));
    }
}
