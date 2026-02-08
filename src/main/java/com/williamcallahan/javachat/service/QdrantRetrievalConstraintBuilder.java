package com.williamcallahan.javachat.service;

import static io.qdrant.client.ConditionFactory.matchKeyword;

import io.qdrant.client.grpc.Common.Filter;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Converts retrieval constraints into Qdrant payload filters.
 *
 * <p>This keeps filter construction in one place so retrieval services apply
 * consistent field names across dense and sparse query stages.</p>
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
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");

        Filter.Builder filterBuilder = Filter.newBuilder();
        int mustConditionCount = 0;

        if (!retrievalConstraint.docVersion().isBlank()) {
            filterBuilder.addMust(matchKeyword("docVersion", Objects.requireNonNull(retrievalConstraint.docVersion())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.sourceKind().isBlank()) {
            filterBuilder.addMust(matchKeyword("sourceKind", Objects.requireNonNull(retrievalConstraint.sourceKind())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.docType().isBlank()) {
            filterBuilder.addMust(matchKeyword("docType", Objects.requireNonNull(retrievalConstraint.docType())));
            mustConditionCount++;
        }
        if (!retrievalConstraint.sourceName().isBlank()) {
            filterBuilder.addMust(matchKeyword("sourceName", Objects.requireNonNull(retrievalConstraint.sourceName())));
            mustConditionCount++;
        }

        if (mustConditionCount == 0) {
            return Optional.empty();
        }
        return Optional.of(filterBuilder.build());
    }
}
