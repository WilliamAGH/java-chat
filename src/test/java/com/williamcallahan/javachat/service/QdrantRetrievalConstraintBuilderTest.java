package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.qdrant.client.grpc.Common.Filter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies conversion of retrieval constraints into Qdrant filters.
 */
class QdrantRetrievalConstraintBuilderTest {

    private final QdrantRetrievalConstraintBuilder builder = new QdrantRetrievalConstraintBuilder();

    @Test
    void returnsEmptyFilterForUnconstrainedInput() {
        Optional<Filter> optionalFilter = builder.buildFilter(RetrievalConstraint.none());

        assertTrue(optionalFilter.isEmpty());
    }

    @Test
    void buildsMustConditionsForConstrainedInput() {
        RetrievalConstraint retrievalConstraint = new RetrievalConstraint("25", "official", "api-docs", "");
        Optional<Filter> optionalFilter = builder.buildFilter(retrievalConstraint);

        assertTrue(optionalFilter.isPresent());
        Filter qdrantFilter = optionalFilter.get();
        assertFalse(qdrantFilter.getMustList().isEmpty());
        assertTrue(qdrantFilter.toString().contains("docVersion"));
        assertTrue(qdrantFilter.toString().contains("sourceKind"));
        assertTrue(qdrantFilter.toString().contains("docType"));
    }
}
