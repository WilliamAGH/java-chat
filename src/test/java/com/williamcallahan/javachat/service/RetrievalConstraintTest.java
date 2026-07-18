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
        List<String> requestedDocSets = new ArrayList<>();

        RetrievalConstraint retrievalConstraint = new RetrievalConstraint("", "", "", "", requestedDocSets);
        requestedDocSets.add("dev-java");

        assertTrue(retrievalConstraint.docSet().isEmpty());
        assertFalse(retrievalConstraint.hasServerSideConstraint());
        assertThrows(
                UnsupportedOperationException.class,
                () -> retrievalConstraint.docSet().add("dev-java"));
    }
}
