package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import java.util.List;
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
        DocsSourceRegistry.JavaApiDocumentationSource representedJavaApiSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        List<String> allowedDocSet = DocsSourceRegistry.officialDocumentationSourceIdentities();
        RetrievalConstraint retrievalConstraint = new RetrievalConstraint(
                representedJavaApiSource.javaRelease(),
                "official",
                DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE,
                "",
                allowedDocSet);
        Optional<Filter> optionalFilter = builder.buildFilter(retrievalConstraint);

        assertTrue(optionalFilter.isPresent());
        Filter qdrantFilter = optionalFilter.get();
        assertFalse(qdrantFilter.getMustList().isEmpty());
        assertTrue(qdrantFilter.toString().contains("docVersion"));
        assertTrue(qdrantFilter.toString().contains("sourceKind"));
        assertTrue(qdrantFilter.toString().contains("docType"));
        Condition docSetCondition = qdrantFilter.getMustList().stream()
                .filter(mustCondition ->
                        "docSet".equals(mustCondition.getField().getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                allowedDocSet,
                docSetCondition.getField().getMatch().getKeywords().getStringsList());
    }

    @Test
    void rejectsEmptyOfficialDocSetConstraint() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalConstraint.forOfficialDocSets(List.of()));
    }
}
