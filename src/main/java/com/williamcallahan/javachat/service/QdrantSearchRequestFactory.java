package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Builds Qdrant query and scroll requests for hybrid retrieval and citation discovery. */
@Component
public final class QdrantSearchRequestFactory {

    QueryPoints buildHybridQuery(
            String collectionName,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter,
            String denseVectorName,
            String sparseVectorName,
            int prefetchLimit,
            int reciprocalRankFusionK,
            int resultLimit) {
        QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(resultLimit);
        retrievalFilter.ifPresent(queryBuilder::setFilter);

        PrefetchQuery.Builder densePrefetchBuilder = PrefetchQuery.newBuilder()
                .setQuery(nearest(Objects.requireNonNull(denseVector, "denseVector")))
                .setUsing(denseVectorName)
                .setLimit(prefetchLimit);
        retrievalFilter.ifPresent(densePrefetchBuilder::setFilter);
        queryBuilder.addPrefetch(densePrefetchBuilder.build());

        if (!sparseVector.indices().isEmpty()) {
            PrefetchQuery.Builder sparsePrefetchBuilder = PrefetchQuery.newBuilder()
                    .setQuery(nearest(
                            Objects.requireNonNull(sparseVector.termFrequencies(), "sparse term frequencies"),
                            Objects.requireNonNull(sparseVector.integerIndices(), "sparse integer indices")))
                    .setUsing(sparseVectorName)
                    .setLimit(prefetchLimit);
            retrievalFilter.ifPresent(sparsePrefetchBuilder::setFilter);
            queryBuilder.addPrefetch(sparsePrefetchBuilder.build());
        }

        queryBuilder.setQuery(rrf(Objects.requireNonNull(
                Rrf.newBuilder().setK(reciprocalRankFusionK).build(), "reciprocal rank fusion")));
        return queryBuilder.build();
    }

    QueryPoints buildDocumentationCitationQuery(
            String documentationCollectionName,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter,
            String sparseVectorName,
            int citationCandidateLimit) {
        QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                .setCollectionName(documentationCollectionName)
                .setQuery(nearest(
                        Objects.requireNonNull(sparseVector.termFrequencies(), "sparse term frequencies"),
                        Objects.requireNonNull(sparseVector.integerIndices(), "sparse integer indices")))
                .setUsing(sparseVectorName)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(citationCandidateLimit);
        retrievalFilter.ifPresent(queryBuilder::setFilter);
        return queryBuilder.build();
    }

    ScrollPoints buildExactCitationScroll(
            String documentationCollectionName, Filter exactCitationFilter, int citationCandidateLimit) {
        return ScrollPoints.newBuilder()
                .setCollectionName(documentationCollectionName)
                .setFilter(exactCitationFilter)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(citationCandidateLimit)
                .build();
    }
}
