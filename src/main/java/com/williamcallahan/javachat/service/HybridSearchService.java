package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.VectorInputFactory.vectorInput;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
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
 * Executes hybrid (dense + sparse) search across all Qdrant collections using RRF fusion.
 *
 * <p>For each collection, a Qdrant Query API request is issued with two prefetch stages
 * (dense nearest-neighbor and sparse BM25-style lexical search) fused via reciprocal rank
 * fusion (RRF). Queries are fanned out to all configured collections in parallel and results
 * are deduplicated by point UUID before returning top-K.</p>
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

    private static final String PAYLOAD_DOC_CONTENT = QdrantPayloadFieldSchema.DOC_CONTENT_FIELD;
    private static final int MAX_FAILURE_DETAIL_LENGTH = 240;

    /**
     * Groups the three collaborators that encode a query into search inputs (dense vector,
     * sparse vector, and Qdrant metadata filter). These are always invoked together at
     * the start of every search.
     *
     * @param embeddingClient embedding client for dense query vectors
     * @param sparseVectorEncoder sparse encoder for lexical query vectors
     * @param constraintBuilder builder for Qdrant payload filters from retrieval constraints
     */
    record QueryEncodingServices(
            EmbeddingClient embeddingClient,
            LexicalSparseVectorEncoder sparseVectorEncoder,
            QdrantRetrievalConstraintBuilder constraintBuilder) {
        QueryEncodingServices {
            Objects.requireNonNull(embeddingClient, "embeddingClient");
            Objects.requireNonNull(sparseVectorEncoder, "sparseVectorEncoder");
            Objects.requireNonNull(constraintBuilder, "constraintBuilder");
        }
    }

    private final QdrantClient qdrantClient;
    private final QueryEncodingServices queryEncoding;
    private final AppProperties appProperties;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    /**
     * Wires gRPC client and encoding dependencies for hybrid search.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param embeddingClient embedding client for dense query vectors
     * @param sparseVectorEncoder sparse encoder for lexical query vectors
     * @param constraintBuilder builder for Qdrant payload filters
     * @param appProperties application configuration
     * @param gitHubCollectionDiscovery optional discovery of dynamically created GitHub collections
     */
    public HybridSearchService(
            QdrantClient qdrantClient,
            EmbeddingClient embeddingClient,
            LexicalSparseVectorEncoder sparseVectorEncoder,
            QdrantRetrievalConstraintBuilder constraintBuilder,
            AppProperties appProperties,
            Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.queryEncoding = new QueryEncodingServices(embeddingClient, sparseVectorEncoder, constraintBuilder);
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

        float[] denseVector = queryEncoding.embeddingClient().embed(query);
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(query);
        Optional<Filter> retrievalFilter = queryEncoding.constraintBuilder().buildFilter(retrievalConstraint);
        EncodedQuery encodedQuery = new EncodedQuery(denseVector, sparseVector, retrievalFilter);

        List<String> collectionNames = allCollectionNames();
        HybridQueryConfig queryConfig = HybridQueryConfig.fromProperties(appProperties);

        List<CompletableFuture<List<ScoredPoint>>> futures = new ArrayList<>(collectionNames.size());
        for (String collection : collectionNames) {
            QueryPoints queryRequest = Objects.requireNonNull(
                    buildHybridQueryRequest(collection, encodedQuery, queryConfig, topK), "QueryPoints");
            CompletableFuture<List<ScoredPoint>> future = toCompletableFuture(qdrantClient.queryAsync(queryRequest));
            futures.add(future);
        }

        Map<String, ScoredResult> scoredPointsByUuid = new LinkedHashMap<>();
        List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures = new ArrayList<>();
        collectFanOutResults(
                futures, collectionNames, queryConfig.queryTimeout(), scoredPointsByUuid, collectionFailures);

        if (!collectionFailures.isEmpty() && queryConfig.failOnPartialSearchError()) {
            throw new HybridSearchPartialFailureException(
                    "Hybrid retrieval failed for " + collectionFailures.size() + " collection(s)", collectionFailures);
        }

        List<Document> rankedDocuments = scoredPointsByUuid.values().stream()
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(topK)
                .map(this::toDocument)
                .toList();
        List<HybridSearchNotice> retrievalNotices =
                collectionFailures.stream().map(HybridSearchService::toNotice).toList();
        return new SearchOutcome(rankedDocuments, retrievalNotices);
    }

    /**
     * Groups Qdrant hybrid query configuration that travels together across search operations.
     *
     * @param denseVectorName Qdrant named vector key for dense embeddings
     * @param sparseVectorName Qdrant named vector key for sparse tokens
     * @param prefetchLimit per-collection prefetch candidate count
     * @param rrfK reciprocal rank fusion k parameter
     * @param queryTimeout fan-out timeout per collection
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
            AppProperties.Qdrant qdrant = appProperties.getQdrant();
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
    }

    private QueryPoints buildHybridQueryRequest(
            String collection, EncodedQuery encodedQuery, HybridQueryConfig queryConfig, int limit) {

        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(limit);
        encodedQuery.retrievalFilter().ifPresent(builder::setFilter);

        PrefetchQuery.Builder densePrefetchBuilder = PrefetchQuery.newBuilder()
                .setQuery(nearest(encodedQuery.denseVector()))
                .setUsing(queryConfig.denseVectorName())
                .setLimit(queryConfig.prefetchLimit());
        encodedQuery.retrievalFilter().ifPresent(densePrefetchBuilder::setFilter);
        builder.addPrefetch(densePrefetchBuilder.build());

        if (!encodedQuery.sparseVector().indices().isEmpty()) {
            PrefetchQuery.Builder sparsePrefetchBuilder = PrefetchQuery.newBuilder()
                    .setQuery(nearest(vectorInput(
                            encodedQuery.sparseVector().values(),
                            encodedQuery.sparseVector().integerIndices())))
                    .setUsing(queryConfig.sparseVectorName())
                    .setLimit(queryConfig.prefetchLimit());
            encodedQuery.retrievalFilter().ifPresent(sparsePrefetchBuilder::setFilter);
            builder.addPrefetch(sparsePrefetchBuilder.build());
        }

        builder.setQuery(rrf(Rrf.newBuilder().setK(queryConfig.rrfK()).build()));
        return builder.build();
    }

    private void collectFanOutResults(
            List<CompletableFuture<List<ScoredPoint>>> futures,
            List<String> collectionNames,
            Duration timeout,
            Map<String, ScoredResult> scoredPointsByUuid,
            List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures) {

        for (int i = 0; i < futures.size(); i++) {
            String collection = collectionNames.get(i);
            try {
                List<ScoredPoint> points = futures.get(i).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                mergePoints(points, collection, scoredPointsByUuid);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                futures.get(i).cancel(true);
                log.warn("[QDRANT] Search interrupted for collection={}", collection);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, "Interrupted", "Hybrid query was interrupted"));
            } catch (ExecutionException executionException) {
                Throwable cause = executionException.getCause();
                String exceptionType = cause == null
                        ? executionException.getClass().getSimpleName()
                        : cause.getClass().getSimpleName();
                String failureMessage = cause == null ? executionException.getMessage() : cause.getMessage();
                log.warn("[QDRANT] Search failed for collection={} (exceptionType={})", collection, exceptionType);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, exceptionType, sanitizeFailureDetails(failureMessage)));
            } catch (TimeoutException _) {
                futures.get(i).cancel(true);
                log.warn("[QDRANT] Search timed out for collection={}", collection);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, "Timeout", "Hybrid query exceeded timeout " + timeout.toMillis() + "ms"));
            }
        }
    }

    private static void mergePoints(
            List<ScoredPoint> points, String collection, Map<String, ScoredResult> scoredPointsByUuid) {
        for (ScoredPoint point : points) {
            String pointId = extractPointId(point);
            ScoredResult existing = scoredPointsByUuid.get(pointId);
            if (existing == null || point.getScore() > existing.score()) {
                scoredPointsByUuid.put(pointId, new ScoredResult(pointId, point.getScore(), point, collection));
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

    private Document toDocument(ScoredResult result) {
        ScoredPoint point = result.point();

        Document document = Document.builder()
                .id(result.id())
                .text(extractPayloadString(point.getPayloadMap(), PAYLOAD_DOC_CONTENT))
                .build();

        // Keep metadata explicit and typed; do not attempt to round-trip arbitrary payloads.
        applyKnownMetadata(point.getPayloadMap(), document);
        document.getMetadata().put("score", result.score());
        document.getMetadata().put("collection", result.collection());

        return document;
    }

    private static void applyKnownMetadata(Map<String, Value> payload, Document target) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        for (String stringField : QdrantPayloadFieldSchema.STRING_METADATA_FIELDS) {
            putIfPresentString(payload, target, stringField);
        }
        for (String integerField : QdrantPayloadFieldSchema.INTEGER_METADATA_FIELDS) {
            putIfPresentInteger(payload, target, integerField);
        }
    }

    private static void putIfPresentString(Map<String, Value> payload, Document target, String key) {
        String stringFieldValue = extractPayloadString(payload, key);
        if (!stringFieldValue.isBlank()) {
            target.getMetadata().put(key, stringFieldValue);
        }
    }

    private static void putIfPresentInteger(Map<String, Value> payload, Document target, String key) {
        extractPayloadInteger(payload, key)
                .ifPresent(value -> target.getMetadata().put(key, value));
    }

    private static String extractPayloadString(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        Value payloadValue = payload.get(key);
        if (payloadValue == null) {
            return "";
        }
        if (payloadValue.getKindCase() == Value.KindCase.STRING_VALUE) {
            return payloadValue.getStringValue();
        }
        return fromValue(payloadValue).map(String::valueOf).orElse("");
    }

    private static Optional<Integer> extractPayloadInteger(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        Value payloadValue = payload.get(key);
        if (payloadValue == null) {
            return Optional.empty();
        }
        if (payloadValue.getKindCase() == Value.KindCase.INTEGER_VALUE) {
            long integerValue = payloadValue.getIntegerValue();
            if (integerValue > Integer.MAX_VALUE) {
                return Optional.of(Integer.MAX_VALUE);
            }
            if (integerValue < Integer.MIN_VALUE) {
                return Optional.of(Integer.MIN_VALUE);
            }
            return Optional.of((int) integerValue);
        }
        Optional<Object> maybePayloadValue = fromValue(payloadValue);
        return maybePayloadValue
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
    }

    private static Optional<Object> fromValue(Value payloadValue) {
        if (payloadValue == null) {
            return Optional.empty();
        }
        return switch (payloadValue.getKindCase()) {
            case STRING_VALUE -> Optional.of(payloadValue.getStringValue());
            case INTEGER_VALUE -> Optional.of(payloadValue.getIntegerValue());
            case DOUBLE_VALUE -> Optional.of(payloadValue.getDoubleValue());
            case BOOL_VALUE -> Optional.of(payloadValue.getBoolValue());
            case NULL_VALUE -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private static String extractPointId(ScoredPoint point) {
        if (point.getId().hasUuid()) {
            return point.getId().getUuid();
        }
        return String.valueOf(point.getId().getNum());
    }

    private static <T> CompletableFuture<T> toCompletableFuture(
            com.google.common.util.concurrent.ListenableFuture<T> listenableFuture) {
        CompletableFuture<T> completable = new CompletableFuture<>();
        com.google.common.util.concurrent.Futures.addCallback(
                listenableFuture,
                new com.google.common.util.concurrent.FutureCallback<>() {
                    @Override
                    public void onSuccess(T result) {
                        completable.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        completable.completeExceptionally(throwable);
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor());
        return completable;
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

    private record ScoredResult(String id, double score, ScoredPoint point, String collection) {}
}
