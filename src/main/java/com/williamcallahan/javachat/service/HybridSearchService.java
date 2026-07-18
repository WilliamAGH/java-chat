package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.config.QdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Executes hybrid search across all Qdrant collections and sparse-only official citation search.
 *
 * <p>For each collection, a Qdrant Query API request is issued with two prefetch stages
 * (dense nearest-neighbor and sparse BM25-style lexical search) fused via reciprocal rank
 * fusion (RRF). Queries are fanned out to all configured collections in parallel and results
 * are deduplicated by point UUID before returning top-K. Citation discovery sends one direct sparse
 * query only to the canonical documentation collection.</p>
 *
 * <p>Verified API contract (Step 0): this adapter uses direct {@code io.qdrant:client} 1.16.2
 * primitives rather than Spring AI VectorStore abstractions. Hybrid behavior depends on
 * {@code QueryPoints.prefetch + QueryFactory.rrf(...)}, sparse query vectors are encoded with
 * {@code VectorInputFactory.vectorInput(values, indices)}, and {@code using} names must match
 * collection schema keys configured in {@code app.qdrant.*}.</p>
 */
@Service
public class HybridSearchService {
    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final int MAX_FAILURE_DETAIL_LENGTH = 240;
    private static final long MINIMUM_QDRANT_QUERY_DURATION_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final QdrantClient qdrantClient;
    private final QueryEncodingServices queryEncoding;
    private final AppProperties appProperties;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    /**
     * Wires gRPC client and encoding dependencies for hybrid search.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param queryEncoding grouped query-encoding collaborators
     * @param appProperties application configuration
     * @param gitHubCollectionDiscovery optional discovery of dynamically created GitHub collections
     */
    public HybridSearchService(
            QdrantClient qdrantClient,
            QueryEncodingServices queryEncoding,
            AppProperties appProperties,
            Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.queryEncoding = Objects.requireNonNull(queryEncoding, "queryEncoding");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.gitHubCollectionDiscovery = Objects.requireNonNull(gitHubCollectionDiscovery, "gitHubCollectionDiscovery");
    }

    /**
     * Captures one retrieval notice generated during non-strict hybrid fan-out.
     *
     * @param summary concise summary for UI status events
     * @param details detailed retrieval context for diagnostics
     */
    public record HybridSearchNotice(String summary, String details) {
        public HybridSearchNotice {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary cannot be null or blank");
            }
            details = details == null ? "" : details;
        }
    }

    /**
     * Captures hybrid search results and optional non-fatal notices.
     *
     * @param documents retrieved documents
     * @param notices retrieval notices for non-strict partial failures
     */
    public record SearchOutcome(List<Document> documents, List<HybridSearchNotice> notices) {
        public SearchOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    /**
     * Performs hybrid search across all configured collections.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @return documents sorted by fused score (highest first)
     */
    public List<Document> search(String query, int topK) {
        return searchOutcome(query, topK, RetrievalConstraint.none()).documents();
    }

    /**
     * Performs hybrid search across all configured collections and returns retrieval notices.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @param retrievalConstraint retrieval metadata constraint for server-side filtering
     * @return search outcome containing documents and optional non-fatal notices
     */
    public SearchOutcome searchOutcome(String query, int topK, RetrievalConstraint retrievalConstraint) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        if (query.isBlank() || topK <= 0) {
            return new SearchOutcome(List.of(), List.of());
        }

        float[] denseVector = queryEncoding.embeddingClient().embed(query, LlmGatewayTier.LIVE);
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(query);
        Optional<Filter> retrievalFilter = queryEncoding.constraintBuilder().buildFilter(retrievalConstraint);
        EncodedQuery encodedQuery = new EncodedQuery(denseVector, sparseVector, retrievalFilter);

        List<String> collectionNames = allCollectionNames();
        HybridQueryConfig queryConfig = HybridQueryConfig.fromProperties(appProperties);

        Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection =
                LinkedHashMap.newLinkedHashMap(collectionNames.size());
        long queryDeadlineNanos = queryDeadlineNanos(queryConfig.queryTimeout());
        for (String collectionName : collectionNames) {
            QueryPoints queryRequest = Objects.requireNonNull(
                    buildHybridQueryRequest(collectionName, encodedQuery, queryConfig, topK), "QueryPoints");
            CompletableFuture<List<ScoredPoint>> collectionQueryFuture =
                    dispatchQueryBeforeDeadline(queryRequest, queryDeadlineNanos);
            futuresByCollection.put(collectionName, collectionQueryFuture);
        }

        CollectionQueryDispatch queryDispatch =
                new CollectionQueryDispatch(futuresByCollection, queryConfig.queryTimeout(), queryDeadlineNanos);
        return collectSearchOutcome(queryDispatch, topK, queryConfig, CollectionFailurePolicy.CONFIGURED);
    }

    /**
     * Searches the canonical documentation collection for citation candidates using sparse lexical retrieval only.
     *
     * <p>This operation does not create a dense embedding, issue prefetch queries, perform RRF fusion,
     * or include dynamically discovered GitHub collections.</p>
     *
     * @param query citation-discovery query text
     * @param topK maximum number of citation candidates to return
     * @param retrievalConstraint official documentation metadata constraint
     * @return sparse search outcome containing documents and optional non-fatal notices
     */
    public SearchOutcome searchDocumentationCitationsOutcome(
            String query, int topK, RetrievalConstraint retrievalConstraint) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        if (query.isBlank() || topK <= 0) {
            return new SearchOutcome(List.of(), List.of());
        }

        String expandedCitationQuery = JavaApiMethodSelector.expandForSparseCitationQuery(query);
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(expandedCitationQuery);
        if (sparseVector.indices().isEmpty()) {
            return new SearchOutcome(List.of(), List.of());
        }

        Optional<Filter> retrievalFilter = queryEncoding.constraintBuilder().buildFilter(retrievalConstraint);
        EncodedCitationQuery encodedCitationQuery = new EncodedCitationQuery(sparseVector, retrievalFilter);
        HybridQueryConfig queryConfig = HybridQueryConfig.fromProperties(appProperties);
        String documentationCollectionName =
                appProperties.getQdrant().getCollections().getDocs();
        long queryDeadlineNanos = queryDeadlineNanos(queryConfig.queryTimeout());
        QueryPoints queryRequest = buildDocumentationCitationQueryRequest(
                documentationCollectionName, encodedCitationQuery, queryConfig, topK);
        CompletableFuture<List<ScoredPoint>> documentationQueryFuture =
                dispatchQueryBeforeDeadline(queryRequest, queryDeadlineNanos);
        Map<String, CompletableFuture<List<ScoredPoint>>> documentationQueryByCollection =
                Map.of(documentationCollectionName, documentationQueryFuture);
        CollectionQueryDispatch queryDispatch = new CollectionQueryDispatch(
                documentationQueryByCollection, queryConfig.queryTimeout(), queryDeadlineNanos);
        return collectSearchOutcome(queryDispatch, topK, queryConfig, CollectionFailurePolicy.STRICT);
    }

    private SearchOutcome collectSearchOutcome(
            CollectionQueryDispatch queryDispatch,
            int topK,
            HybridQueryConfig queryConfig,
            CollectionFailurePolicy collectionFailurePolicy) {
        Map<String, ScoredPointMatch> scoredPointsByUuid = new LinkedHashMap<>();
        List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures = new ArrayList<>();
        collectFanOutResults(queryDispatch, scoredPointsByUuid, collectionFailures);

        if (!collectionFailures.isEmpty() && collectionFailurePolicy.failsSearch(queryConfig)) {
            throw new HybridSearchPartialFailureException(
                    "Qdrant retrieval failed for " + collectionFailures.size() + " collection(s)", collectionFailures);
        }

        List<Document> rankedDocuments = scoredPointsByUuid.values().stream()
                .sorted(Comparator.comparingDouble(ScoredPointMatch::score).reversed())
                .limit(topK)
                .map(scoredPointMatch -> QdrantScoredPointDocumentMapper.toDocument(
                        scoredPointMatch.point(),
                        scoredPointMatch.id(),
                        scoredPointMatch.score(),
                        scoredPointMatch.collectionName()))
                .toList();
        List<HybridSearchNotice> retrievalNotices =
                collectionFailures.stream().map(HybridSearchService::toNotice).toList();
        return new SearchOutcome(rankedDocuments, retrievalNotices);
    }

    private static long queryDeadlineNanos(Duration queryTimeout) {
        return System.nanoTime() + queryTimeout.toNanos();
    }

    private CompletableFuture<List<ScoredPoint>> dispatchQueryBeforeDeadline(
            QueryPoints queryRequest, long queryDeadlineNanos) {
        long remainingQueryDurationNanos = queryDeadlineNanos - System.nanoTime();
        if (remainingQueryDurationNanos < MINIMUM_QDRANT_QUERY_DURATION_NANOS) {
            return new CompletableFuture<>();
        }
        Duration remainingQueryDuration = Duration.ofNanos(remainingQueryDurationNanos);
        try {
            return QdrantListenableFutureBridge.toCompletableFuture(
                    qdrantClient.queryAsync(queryRequest, remainingQueryDuration));
        } catch (RuntimeException queryDispatchFailure) {
            return CompletableFuture.failedFuture(queryDispatchFailure);
        }
    }

    /**
     * Groups Qdrant hybrid query configuration that travels together across search operations.
     *
     * @param denseVectorName Qdrant named vector key for dense embeddings
     * @param sparseVectorName Qdrant named vector key for sparse tokens
     * @param prefetchLimit per-collection prefetch candidate count
     * @param rrfK reciprocal rank fusion k parameter
     * @param queryTimeout shared timeout budget for the complete collection fan-out
     * @param failOnPartialSearchError whether partial collection failures are fatal
     */
    record HybridQueryConfig(
            String denseVectorName,
            String sparseVectorName,
            int prefetchLimit,
            int rrfK,
            Duration queryTimeout,
            boolean failOnPartialSearchError) {

        HybridQueryConfig {
            Objects.requireNonNull(denseVectorName, "denseVectorName");
            Objects.requireNonNull(sparseVectorName, "sparseVectorName");
            Objects.requireNonNull(queryTimeout, "queryTimeout");
        }

        static HybridQueryConfig fromProperties(AppProperties appProperties) {
            QdrantProperties qdrant = appProperties.getQdrant();
            return new HybridQueryConfig(
                    qdrant.getDenseVectorName(),
                    qdrant.getSparseVectorName(),
                    qdrant.getPrefetchLimit(),
                    qdrant.getRrfK(),
                    qdrant.getQueryTimeout(),
                    qdrant.isFailOnPartialSearchError());
        }
    }

    /**
     * Groups the three query-encoding outputs that always travel together into a single search call.
     *
     * @param denseVector dense embedding vector for nearest-neighbor prefetch
     * @param sparseVector sparse BM25-style token vector for lexical prefetch
     * @param retrievalFilter optional Qdrant metadata filter from retrieval constraints
     */
    private record EncodedQuery(
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter) {
        EncodedQuery {
            Objects.requireNonNull(denseVector, "denseVector");
            Objects.requireNonNull(sparseVector, "sparseVector");
            Objects.requireNonNull(retrievalFilter, "retrievalFilter");
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            EncodedQuery that = (EncodedQuery) obj;
            return java.util.Arrays.equals(denseVector, that.denseVector)
                    && java.util.Objects.equals(sparseVector, that.sparseVector)
                    && java.util.Objects.equals(retrievalFilter, that.retrievalFilter);
        }

        @Override
        public int hashCode() {
            int hash = java.util.Objects.hash(sparseVector, retrievalFilter);
            hash = 31 * hash + java.util.Arrays.hashCode(denseVector);
            return hash;
        }

        @Override
        public String toString() {
            return "EncodedQuery{" + "denseVector="
                    + java.util.Arrays.toString(denseVector) + ", sparseVector="
                    + sparseVector + ", retrievalFilter="
                    + retrievalFilter + '}';
        }
    }

    /** Groups sparse encoding and its exact metadata filter for citation discovery. */
    private record EncodedCitationQuery(
            LexicalSparseVectorEncoder.SparseVector sparseVector, Optional<Filter> retrievalFilter) {
        EncodedCitationQuery {
            Objects.requireNonNull(sparseVector, "sparseVector");
            Objects.requireNonNull(retrievalFilter, "retrievalFilter");
        }
    }

    /** Keeps every collection wait on the timeout deadline established before fan-out dispatch. */
    private record CollectionQueryDispatch(
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection,
            Duration queryTimeout,
            long queryDeadlineNanos) {
        CollectionQueryDispatch {
            Objects.requireNonNull(futuresByCollection, "futuresByCollection");
            Objects.requireNonNull(queryTimeout, "queryTimeout");
        }
    }

    /** Selects whether collection query failures follow configuration or always fail retrieval. */
    private enum CollectionFailurePolicy {
        CONFIGURED,
        STRICT;

        private boolean failsSearch(HybridQueryConfig queryConfig) {
            return this == STRICT || queryConfig.failOnPartialSearchError();
        }
    }

    private QueryPoints buildHybridQueryRequest(
            String collectionName, EncodedQuery encodedQuery, HybridQueryConfig queryConfig, int limit) {

        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(limit);
        encodedQuery.retrievalFilter().ifPresent(builder::setFilter);

        PrefetchQuery.Builder densePrefetchBuilder = PrefetchQuery.newBuilder()
                .setQuery(nearest(Objects.requireNonNull(encodedQuery.denseVector())))
                .setUsing(queryConfig.denseVectorName())
                .setLimit(queryConfig.prefetchLimit());
        encodedQuery.retrievalFilter().ifPresent(densePrefetchBuilder::setFilter);
        builder.addPrefetch(densePrefetchBuilder.build());

        if (!encodedQuery.sparseVector().indices().isEmpty()) {
            PrefetchQuery.Builder sparsePrefetchBuilder = PrefetchQuery.newBuilder()
                    .setQuery(nearest(
                            Objects.requireNonNull(encodedQuery.sparseVector().termFrequencies()),
                            Objects.requireNonNull(encodedQuery.sparseVector().integerIndices())))
                    .setUsing(queryConfig.sparseVectorName())
                    .setLimit(queryConfig.prefetchLimit());
            encodedQuery.retrievalFilter().ifPresent(sparsePrefetchBuilder::setFilter);
            builder.addPrefetch(sparsePrefetchBuilder.build());
        }

        builder.setQuery(rrf(
                Objects.requireNonNull(Rrf.newBuilder().setK(queryConfig.rrfK()).build())));
        return builder.build();
    }

    private QueryPoints buildDocumentationCitationQueryRequest(
            String documentationCollectionName,
            EncodedCitationQuery encodedCitationQuery,
            HybridQueryConfig queryConfig,
            int citationCandidateLimit) {
        QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                .setCollectionName(documentationCollectionName)
                .setQuery(nearest(
                        Objects.requireNonNull(
                                encodedCitationQuery.sparseVector().termFrequencies()),
                        Objects.requireNonNull(
                                encodedCitationQuery.sparseVector().integerIndices())))
                .setUsing(queryConfig.sparseVectorName())
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(citationCandidateLimit);
        encodedCitationQuery.retrievalFilter().ifPresent(queryBuilder::setFilter);
        return queryBuilder.build();
    }

    private void collectFanOutResults(
            CollectionQueryDispatch queryDispatch,
            Map<String, ScoredPointMatch> scoredPointsByUuid,
            List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures) {

        for (Map.Entry<String, CompletableFuture<List<ScoredPoint>>> collectionQueryEntry :
                queryDispatch.futuresByCollection().entrySet()) {
            String collectionName = collectionQueryEntry.getKey();
            CompletableFuture<List<ScoredPoint>> collectionQueryFuture = collectionQueryEntry.getValue();
            try {
                long remainingWaitNanos = Math.max(0L, queryDispatch.queryDeadlineNanos() - System.nanoTime());
                List<ScoredPoint> scoredPoints = collectionQueryFuture.get(remainingWaitNanos, TimeUnit.NANOSECONDS);
                mergePoints(scoredPoints, collectionName, scoredPointsByUuid);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                cancelPendingQueries(queryDispatch);
                log.warn("[QDRANT] Search interrupted for collection={}", collectionName);
                HybridSearchPartialFailureException.CollectionSearchFailure interruptionFailure =
                        new HybridSearchPartialFailureException.CollectionSearchFailure(
                                collectionName, "Interrupted", "Qdrant query was interrupted");
                throw new HybridSearchPartialFailureException(
                        "Qdrant retrieval was interrupted", List.of(interruptionFailure));
            } catch (ExecutionException executionException) {
                Throwable cause = executionException.getCause();
                String exceptionType = cause == null
                        ? executionException.getClass().getSimpleName()
                        : cause.getClass().getSimpleName();
                String failureMessage = cause == null ? executionException.getMessage() : cause.getMessage();
                log.warn("[QDRANT] Search failed for collection={} (exceptionType={})", collectionName, exceptionType);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collectionName, exceptionType, sanitizeFailureDetails(failureMessage)));
            } catch (TimeoutException _) {
                collectionQueryFuture.cancel(true);
                log.warn("[QDRANT] Search timed out for collection={}", collectionName);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collectionName,
                        "Timeout",
                        "Qdrant query exceeded timeout "
                                + queryDispatch.queryTimeout().toMillis() + "ms"));
            }
        }
    }

    private static void cancelPendingQueries(CollectionQueryDispatch queryDispatch) {
        queryDispatch.futuresByCollection().values().forEach(queryFuture -> queryFuture.cancel(true));
    }

    private static void mergePoints(
            List<ScoredPoint> points, String collectionName, Map<String, ScoredPointMatch> scoredPointsByUuid) {
        for (ScoredPoint point : points) {
            String pointId = extractPointId(point);
            ScoredPointMatch existing = scoredPointsByUuid.get(pointId);
            if (existing == null || point.getScore() > existing.score()) {
                scoredPointsByUuid.put(pointId, new ScoredPointMatch(pointId, point.getScore(), point, collectionName));
            }
        }
    }

    private List<String> allCollectionNames() {
        List<String> coreCollections =
                appProperties.getQdrant().getCollections().all();
        List<String> gitHubCollections = gitHubCollectionDiscovery
                .map(QdrantGitHubCollectionDiscovery::getDiscoveredCollections)
                .orElse(List.of());
        if (gitHubCollections.isEmpty()) {
            return coreCollections;
        }
        List<String> combined = new ArrayList<>(coreCollections.size() + gitHubCollections.size());
        combined.addAll(coreCollections);
        combined.addAll(gitHubCollections);
        return List.copyOf(combined);
    }

    private static String extractPointId(ScoredPoint point) {
        if (point.getId().hasUuid()) {
            return point.getId().getUuid();
        }
        return String.valueOf(point.getId().getNum());
    }

    private static HybridSearchNotice toNotice(
            HybridSearchPartialFailureException.CollectionSearchFailure collectionSearchFailure) {
        String summary = "Partial retrieval failure in collection " + collectionSearchFailure.collectionName();
        String details = collectionSearchFailure.failureType() + ": " + collectionSearchFailure.failureDetails();
        return new HybridSearchNotice(summary, details);
    }

    private static String sanitizeFailureDetails(String failureDetails) {
        if (failureDetails == null || failureDetails.isBlank()) {
            return "";
        }
        String flattenedFailure =
                failureDetails.replace('\n', ' ').replace('\r', ' ').trim();
        if (flattenedFailure.length() <= MAX_FAILURE_DETAIL_LENGTH) {
            return flattenedFailure;
        }
        return flattenedFailure.substring(0, MAX_FAILURE_DETAIL_LENGTH) + "...";
    }

    private record ScoredPointMatch(String id, double score, ScoredPoint point, String collectionName) {}
}
