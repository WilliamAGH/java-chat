package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Verifies retrieval constraints retain immutable, normalized version and documentation-set ownership. */
class RetrievalConstraintTest {

    @Test
    void copiesDocumentationSetsBeforeExposingThem() {
        List<String> requestedDocSets = new ArrayList<>(List.of("dev-java", "java/java25-complete"));

        RetrievalConstraint retrievalConstraint = RetrievalConstraint.forOfficialDocSets(requestedDocSets);
        requestedDocSets.clear();

        assertEquals(List.of("dev-java", "java/java25-complete"), retrievalConstraint.docSet());
        assertThrows(
                UnsupportedOperationException.class,
                () -> retrievalConstraint.docSet().add("unapproved-doc-set"));
    }

    @Test
    void representsAbsentDocumentationSetsAsAnEmptyImmutableList() {
        RetrievalConstraint retrievalConstraint = RetrievalConstraint.none();

        assertTrue(retrievalConstraint.docSet().isEmpty());
        assertFalse(retrievalConstraint.hasServerSideConstraint());
        assertThrows(
                UnsupportedOperationException.class,
                () -> retrievalConstraint.docSet().add("dev-java"));
    }

    @Test
    void combinesDocumentVersionWithEveryExistingOfficialSourceField() {
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(List.of("dev-java", "java/java17-complete"));

        RetrievalConstraint combinedConstraint = officialDocumentationConstraint.withDocVersions(List.of("17"));

        assertEquals(List.of("17"), combinedConstraint.docVersions());
        assertEquals("official", combinedConstraint.sourceKind());
        assertEquals(officialDocumentationConstraint.docSet(), combinedConstraint.docSet());
    }

    @Test
    void rejectsConflictingDocumentVersions() {
        RetrievalConstraint java17Constraint = RetrievalConstraint.forDocVersions(List.of("17"));

        assertThrows(IllegalArgumentException.class, () -> requireDocumentVersion(java17Constraint, "25"));
    }

    @Test
    void normalizesPluralDocumentVersionsWithoutChangingEncounterOrder() {
        List<String> requestedVersions = new ArrayList<>(List.of(" 21 ", "24", "21", ""));

        RetrievalConstraint retrievalConstraint = RetrievalConstraint.forDocVersions(requestedVersions);
        requestedVersions.clear();

        assertEquals(List.of("21", "24"), retrievalConstraint.docVersions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> retrievalConstraint.docVersions().add("25"));
    }

    @Test
    void intersectsExistingAndRequiredDocumentVersions() {
        RetrievalConstraint allowedVersions = RetrievalConstraint.forDocVersions(List.of("21", "24", "25"));

        RetrievalConstraint intersectedVersions = allowedVersions.withDocVersions(List.of("24", "21"));

        assertEquals(List.of("24", "21"), intersectedVersions.docVersions());
    }

    private static void requireDocumentVersion(
            RetrievalConstraint retrievalConstraint, String requiredDocumentVersion) {
        Objects.requireNonNull(retrievalConstraint.withDocVersions(List.of(requiredDocumentVersion)));
    }
}
