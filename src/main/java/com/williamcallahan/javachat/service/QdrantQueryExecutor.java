package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/**
 * Owns deadline-aware dispatch for Qdrant search operations.
 */
@Component
final class QdrantQueryExecutor {
    private static final long MINIMUM_QDRANT_QUERY_DURATION_NANOS =
            Duration.ofMillis(1).toNanos();

    private final QdrantClient qdrantClient;
    private final QdrantSearchRequestFactory queryRequestFactory;
    private final AppProperties appProperties;

    QdrantQueryExecutor(
            QdrantClient qdrantClient, QdrantSearchRequestFactory queryRequestFactory, AppProperties appProperties) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.queryRequestFactory = Objects.requireNonNull(queryRequestFactory, "queryRequestFactory");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
    }

    Duration queryTimeout() {
        return appProperties.getQdrant().getQueryTimeout();
    }

    QueryPoints buildHybridQuery(
            String collectionName,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter,
            int resultLimit) {
        return queryRequestFactory.buildHybridQuery(
                collectionName, denseVector, sparseVector, retrievalFilter, appProperties.getQdrant(), resultLimit);
    }

    QueryPoints buildDocumentationCitationQuery(
            String documentationCollectionName,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter,
            int citationCandidateLimit) {
        return queryRequestFactory.buildDocumentationCitationQuery(
                documentationCollectionName,
                sparseVector,
                retrievalFilter,
                appProperties.getQdrant().getSparseVectorName(),
                citationCandidateLimit);
    }

    ScrollPoints buildExactCitationScroll(
            String documentationCollectionName, Filter exactCitationFilter, int citationCandidateLimit) {
        return queryRequestFactory.buildExactCitationScroll(
                documentationCollectionName, exactCitationFilter, citationCandidateLimit);
    }

    CompletableFuture<List<ScoredPoint>> queryBeforeDeadline(QueryPoints queryRequest, long queryDeadlineNanos) {
        long remainingQueryDurationNanos = queryDeadlineNanos - System.nanoTime();
        if (remainingQueryDurationNanos < MINIMUM_QDRANT_QUERY_DURATION_NANOS) {
            return QdrantFutureAwaiter.exhaustedQueryBudgetFuture();
        }
        Duration remainingQueryDuration = Duration.ofNanos(remainingQueryDurationNanos);
        try {
            return QdrantListenableFutureBridge.toCompletableFuture(
                    qdrantClient.queryAsync(queryRequest, remainingQueryDuration));
        } catch (RuntimeException queryDispatchFailure) {
            return CompletableFuture.failedFuture(queryDispatchFailure);
        }
    }

    CompletableFuture<List<ScoredPoint>> scrollBeforeDeadline(ScrollPoints scrollRequest, long queryDeadlineNanos) {
        long remainingQueryDurationNanos = queryDeadlineNanos - System.nanoTime();
        if (remainingQueryDurationNanos < MINIMUM_QDRANT_QUERY_DURATION_NANOS) {
            return QdrantFutureAwaiter.exhaustedQueryBudgetFuture();
        }
        Duration remainingQueryDuration = Duration.ofNanos(remainingQueryDurationNanos);
        try {
            return QdrantListenableFutureBridge.transformToCompletableFuture(
                    qdrantClient.scrollAsync(scrollRequest, remainingQueryDuration),
                    scrollResponse -> scrollResponse.getResultList().stream()
                            .map(retrievedPoint -> ScoredPoint.newBuilder()
                                    .setId(retrievedPoint.getId())
                                    .setScore(1.0f)
                                    .putAllPayload(retrievedPoint.getPayloadMap())
                                    .build())
                            .toList());
        } catch (RuntimeException scrollDispatchFailure) {
            return CompletableFuture.failedFuture(scrollDispatchFailure);
        }
    }
}
