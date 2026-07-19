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

    private static final String OFFICIAL_DOCUMENTATION_SOURCE_KIND = "official";
    private static final String UNCONSTRAINED_SOURCE_NAME = "";

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
        List<String> allowedDocumentationSets = DocsSourceRegistry.officialDocumentationSourceIdentities();
        RetrievalConstraint retrievalConstraint =
                officialJavaApiRetrievalConstraint(representedJavaApiSource, allowedDocumentationSets);
        Optional<Filter> optionalFilter = builder.buildFilter(retrievalConstraint);

        assertTrue(optionalFilter.isPresent());
        Filter qdrantFilter = optionalFilter.get();
        assertFalse(qdrantFilter.getMustList().isEmpty());
        assertTrue(qdrantFilter.toString().contains(QdrantPayloadFieldSchema.DOC_VERSION_FIELD));
        assertTrue(qdrantFilter.toString().contains(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD));
        assertTrue(qdrantFilter.toString().contains(QdrantPayloadFieldSchema.DOC_TYPE_FIELD));
        Condition docSetCondition = qdrantFilter.getMustList().stream()
                .filter(mustCondition -> QdrantPayloadFieldSchema.DOC_SET_FIELD.equals(
                        mustCondition.getField().getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                allowedDocumentationSets,
                docSetCondition.getField().getMatch().getKeywords().getStringsList());
    }

    @Test
    void constrainsOneExactJavaApiOverloadUsingItsAuthoritativeAnchor() {
        Optional<Filter> optionalFilter =
                builder.buildCitationFilter(RetrievalConstraint.none(), "What does java.util.List.of(E, E) do?");

        assertTrue(optionalFilter.isPresent());
        Filter qdrantFilter = optionalFilter.get();
        assertKeywordCondition(qdrantFilter, QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, "List.html");
        assertKeywordCondition(qdrantFilter, QdrantPayloadFieldSchema.ANCHOR_FIELD, "of(E,E)");
        assertKeywordCondition(qdrantFilter, QdrantPayloadFieldSchema.PACKAGE_FIELD, "java.util");
    }

    @Test
    void leavesUnparenthesizedJavaApiMethodAsARelevanceSignal() {
        Optional<Filter> optionalFilter =
                builder.buildCitationFilter(RetrievalConstraint.none(), "Explain List.of in Java");

        assertTrue(optionalFilter.isEmpty());
    }

    @Test
    void leavesRuntimeArgumentsAndMultipleSelectorsUnconstrained() {
        Optional<Filter> runtimeArguments =
                builder.buildCitationFilter(RetrievalConstraint.none(), "Explain List.of(first, second)");
        Optional<Filter> comparison =
                builder.buildCitationFilter(RetrievalConstraint.none(), "Compare List.of(E, E) with Set.of(E, E)");

        assertTrue(runtimeArguments.isEmpty());
        assertTrue(comparison.isEmpty());
    }

    @Test
    void rejectsEmptyOfficialDocSetConstraint() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalConstraint.forOfficialDocSets(List.of()));
    }

    private static RetrievalConstraint officialJavaApiRetrievalConstraint(
            DocsSourceRegistry.JavaApiDocumentationSource javaApiDocumentationSource,
            List<String> allowedDocumentationSets) {
        String documentVersion = javaApiDocumentationSource.javaRelease();
        String sourceKind = OFFICIAL_DOCUMENTATION_SOURCE_KIND;
        String documentType = DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE;
        String sourceName = UNCONSTRAINED_SOURCE_NAME;
        return new RetrievalConstraint(documentVersion, sourceKind, documentType, sourceName, allowedDocumentationSets);
    }

    private static void assertKeywordCondition(Filter qdrantFilter, String fieldName, String expectedKeyword) {
        Condition keywordCondition = qdrantFilter.getMustList().stream()
                .filter(mustCondition ->
                        fieldName.equals(mustCondition.getField().getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedKeyword, keywordCondition.getField().getMatch().getKeyword());
    }
}
