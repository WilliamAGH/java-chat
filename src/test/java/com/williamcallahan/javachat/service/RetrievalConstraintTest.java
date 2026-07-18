package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies retrieval constraints retain immutable, normalized documentation-set ownership. */
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

        RetrievalConstraint combinedConstraint = officialDocumentationConstraint.withDocVersion("17");

        assertEquals("17", combinedConstraint.docVersion());
        assertEquals("official", combinedConstraint.sourceKind());
        assertEquals(officialDocumentationConstraint.docSet(), combinedConstraint.docSet());
    }

    @Test
    void rejectsConflictingDocumentVersions() {
        RetrievalConstraint java17Constraint = RetrievalConstraint.forDocVersion("17");

        assertThrows(IllegalArgumentException.class, () -> java17Constraint.withDocVersion("25"));
    }
}
