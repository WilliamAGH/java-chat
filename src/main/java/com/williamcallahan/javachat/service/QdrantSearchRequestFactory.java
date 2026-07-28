package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.QdrantProperties;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.SearchParams;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Builds Qdrant query and scroll requests for hybrid retrieval and citation discovery.
 *
 * <p>Every request transfers only the payload fields that
 * {@link QdrantScoredPointDocumentMapper} reads, because full payloads duplicate the dense
 * document text across the network for every candidate point.</p>
 */
@Component
public final class QdrantSearchRequestFactory {

    /** Payload fields transferred on retrieval, matching the keys the document mapper consumes. */
    private static final List<String> RETRIEVAL_PAYLOAD_FIELDS = Stream.concat(
                    Stream.of(QdrantPayloadFieldSchema.DOC_CONTENT_FIELD),
                    QdrantPayloadFieldSchema.ALL_METADATA_FIELDS.stream())
            .toList();

    QueryPoints buildHybridQuery(
            String collectionName,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter,
            QdrantProperties qdrantProperties,
            int resultLimit) {
        Objects.requireNonNull(qdrantProperties, "qdrantProperties");
        QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setWithPayload(retrievalPayloadSelector())
                .setLimit(resultLimit);
        retrievalFilter.ifPresent(queryBuilder::setFilter);

        PrefetchQuery.Builder densePrefetchBuilder = PrefetchQuery.newBuilder()
                .setQuery(nearest(Objects.requireNonNull(denseVector, "denseVector")))
                .setUsing(qdrantProperties.getDenseVectorName())
                .setParams(SearchParams.newBuilder()
                        .setHnswEf(qdrantProperties.getDensePrefetchHnswEf())
                        .setExact(false)
                        .build())
                .setLimit(qdrantProperties.getPrefetchLimit());
        retrievalFilter.ifPresent(densePrefetchBuilder::setFilter);
        queryBuilder.addPrefetch(densePrefetchBuilder.build());

        if (!sparseVector.indices().isEmpty()) {
            PrefetchQuery.Builder sparsePrefetchBuilder = PrefetchQuery.newBuilder()
                    .setQuery(nearest(
                            Objects.requireNonNull(sparseVector.termFrequencies(), "sparse term frequencies"),
                            Objects.requireNonNull(sparseVector.integerIndices(), "sparse integer indices")))
                    .setUsing(qdrantProperties.getSparseVectorName())
                    .setLimit(qdrantProperties.getPrefetchLimit());
            retrievalFilter.ifPresent(sparsePrefetchBuilder::setFilter);
            queryBuilder.addPrefetch(sparsePrefetchBuilder.build());
        }

        queryBuilder.setQuery(rrf(Objects.requireNonNull(
                Rrf.newBuilder().setK(qdrantProperties.getRrfK()).build(), "reciprocal rank fusion")));
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
                .setWithPayload(retrievalPayloadSelector())
                .setLimit(citationCandidateLimit);
        retrievalFilter.ifPresent(queryBuilder::setFilter);
        return queryBuilder.build();
    }

    ScrollPoints buildExactCitationScroll(
            String documentationCollectionName, Filter exactCitationFilter, int citationCandidateLimit) {
        return ScrollPoints.newBuilder()
                .setCollectionName(documentationCollectionName)
                .setFilter(exactCitationFilter)
                .setWithPayload(retrievalPayloadSelector())
                .setLimit(citationCandidateLimit)
                .build();
    }

    /**
     * Selects exactly the payload fields the document mapper reads, so queries never
     * transfer fields that no consumer looks at.
     */
    private static WithPayloadSelector retrievalPayloadSelector() {
        return WithPayloadSelectorFactory.include(RETRIEVAL_PAYLOAD_FIELDS);
    }
}
