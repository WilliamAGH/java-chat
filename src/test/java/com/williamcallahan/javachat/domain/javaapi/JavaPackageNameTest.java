package com.williamcallahan.javachat.domain.javaapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies exact Java 25 package validation and Javadoc path projection. */
class JavaPackageNameTest {

    @Test
    void acceptsJavaPackageNamesAndProjectsJavadocPaths() {
        JavaPackageName packageName =
                JavaPackageName.from("java.util.concurrent").orElseThrow();

        assertEquals("java.util.concurrent", packageName.qualifiedName());
        assertEquals("java/util/concurrent", packageName.javadocPath());
    }

    @Test
    void rejectsAbsentPaddedAndInvalidPackageNames() {
        List<String> invalidPackageNames = List.of(
                "",
                " ",
                " java.util",
                "java.util ",
                "java..util",
                "java.util.class-use",
                "java.class",
                "java.true",
                "java.false",
                "java.null");

        assertTrue(JavaPackageName.from(null).isEmpty());
        invalidPackageNames.forEach(candidatePackageName ->
                assertTrue(JavaPackageName.from(candidatePackageName).isEmpty()));
    }

    @Test
    void preventsDirectConstructionOfInvalidPackageNames() {
        assertThrows(IllegalArgumentException.class, () -> new JavaPackageName("java.util.class-use"));
    }
}
