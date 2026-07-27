package com.williamcallahan.javachat.service;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import io.qdrant.client.grpc.Common.Filter;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Converts retrieval constraints into Qdrant payload filters.
 *
 * <p>This keeps filter construction in one place so retrieval services apply
 * consistent field names across dense and sparse query stages.</p>
 *
 * <p>Qdrant Java client 1.18.3's {@code ConditionFactory.matchKeywords} encodes one
 * {@code Match.keywords} condition that matches any supplied keyword, so docSet alternatives
 * remain one OR group inside the surrounding MUST filter.</p>
 */
@Component
public class QdrantRetrievalConstraintBuilder {
    /**
     * Builds a Qdrant filter from the provided retrieval constraint.
     *
     * @param retrievalConstraint retrieval constraint
     * @return optional Qdrant filter when at least one constraint is present
     */
    public Optional<Filter> buildFilter(RetrievalConstraint retrievalConstraint) {
        return buildFilter(retrievalConstraint, "");
    }

    /**
     * Builds an official-citation filter that can require one exact Javadoc member.
     *
     * <p>Primary hybrid retrieval does not use this method because a capitalized project type can
     * resemble a Java API selector. Exact Javadoc keys are safe only inside the dedicated official
     * documentation citation search.</p>
     *
     * @param retrievalConstraint official-documentation retrieval constraint
     * @param citationQuery learner query that may contain one exact Java method signature
     * @return optional Qdrant filter when at least one constraint is present
     */
    public Optional<Filter> buildCitationFilter(RetrievalConstraint retrievalConstraint, String citationQuery) {
        Objects.requireNonNull(citationQuery, "citationQuery");
        return buildFilter(retrievalConstraint, citationQuery);
    }

    private Optional<Filter> buildFilter(RetrievalConstraint retrievalConstraint, String exactCitationQuery) {
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");

        Filter.Builder filterBuilder = Filter.newBuilder();
        int mustConditionCount = 0;

        if (!retrievalConstraint.docVersions().isEmpty()) {
            filterBuilder.addMust(
                    matchKeywords(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, retrievalConstraint.docVersions()));
            mustConditionCount++;
        }
        if (!retrievalConstraint.sourceKind().isBlank()) {
            filterBuilder.addMust(matchKeyword(
                    QdrantPayloadFieldSchema.SOURCE_KIND_FIELD,
                    Objects.requireNonNull(retrievalConstraint.sourceKind())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.docType().isBlank()) {
            filterBuilder.addMust(matchKeyword(
                    QdrantPayloadFieldSchema.DOC_TYPE_FIELD, Objects.requireNonNull(retrievalConstraint.docType())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.sourceName().isBlank()) {
            filterBuilder.addMust(matchKeyword(
                    QdrantPayloadFieldSchema.SOURCE_NAME_FIELD,
                    Objects.requireNonNull(retrievalConstraint.sourceName())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.docSet().isEmpty()) {
            filterBuilder.addMust(matchKeywords(QdrantPayloadFieldSchema.DOC_SET_FIELD, retrievalConstraint.docSet()));
            mustConditionCount++;
        }

        Optional<JavaApiMethodSelector> exactOverloadSelector =
                JavaApiMethodSelector.uniqueExactOverloadFromQuery(exactCitationQuery);
        if (exactOverloadSelector.isPresent()) {
            JavaApiMethodSelector selector = exactOverloadSelector.get();
            filterBuilder.addMust(
                    matchKeyword(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, selector.typePageFileName()));
            filterBuilder.addMust(matchKeyword(
                    QdrantPayloadFieldSchema.ANCHOR_FIELD,
                    selector.exactOverloadAnchor().orElseThrow()));
            mustConditionCount += 2;
            if (!selector.packageName().isBlank()) {
                filterBuilder.addMust(matchKeyword(QdrantPayloadFieldSchema.PACKAGE_FIELD, selector.packageName()));
                mustConditionCount++;
            }
        }

        if (mustConditionCount == 0) {
            return Optional.empty();
        }
        return Optional.of(filterBuilder.build());
    }
}
