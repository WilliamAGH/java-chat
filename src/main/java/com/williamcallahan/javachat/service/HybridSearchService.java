package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.VectorInputFactory.vectorInput;

import com.williamcallahan.javachat.config.AppProperties;
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

    private static final String PAYLOAD_DOC_CONTENT = "doc_content";
    private static final int MAX_FAILURE_DETAIL_LENGTH = 240;

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;
    private final LexicalSparseVectorEncoder sparseVectorEncoder;
    private final QdrantRetrievalConstraintBuilder qdrantRetrievalConstraintBuilder;
    private final AppProperties appProperties;

    /**
     * Wires gRPC client and embedding dependencies for hybrid search.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param embeddingClient embedding client for dense query vectors
     * @param sparseVectorEncoder sparse encoder for lexical query vectors
     * @param qdrantRetrievalConstraintBuilder builder for Qdrant payload filters
     * @param appProperties application configuration
     */
    public HybridSearchService(
            QdrantClient qdrantClient,
            EmbeddingClient embeddingClient,
            LexicalSparseVectorEncoder sparseVectorEncoder,
            QdrantRetrievalConstraintBuilder qdrantRetrievalConstraintBuilder,
            AppProperties appProperties) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.sparseVectorEncoder = Objects.requireNonNull(sparseVectorEncoder, "sparseVectorEncoder");
        this.qdrantRetrievalConstraintBuilder =
                Objects.requireNonNull(qdrantRetrievalConstraintBuilder, "qdrantRetrievalConstraintBuilder");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
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

        float[] denseVector = embeddingClient.embed(query);
        LexicalSparseVectorEncoder.SparseVector sparseVector = sparseVectorEncoder.encode(query);
        Optional<Filter> retrievalFilter = qdrantRetrievalConstraintBuilder.buildFilter(retrievalConstraint);

        List<String> collectionNames = allCollectionNames();
        String denseVectorName = appProperties.getQdrant().getDenseVectorName();
        String sparseVectorName = appProperties.getQdrant().getSparseVectorName();
        int prefetchLimit = appProperties.getQdrant().getPrefetchLimit();
        int rrfK = appProperties.getQdrant().getRrfK();
        Duration timeout = appProperties.getQdrant().getQueryTimeout();
        boolean failOnPartialSearchError = appProperties.getQdrant().isFailOnPartialSearchError();

        List<CompletableFuture<List<ScoredPoint>>> futures = new ArrayList<>(collectionNames.size());
        for (String collection : collectionNames) {
            QueryPoints queryRequest = Objects.requireNonNull(
                    buildHybridQueryRequest(
                            collection,
                            denseVector,
                            sparseVector,
                            denseVectorName,
                            sparseVectorName,
                            prefetchLimit,
                            topK,
                            retrievalFilter,
                            rrfK),
                    "QueryPoints");
            CompletableFuture<List<ScoredPoint>> future = toCompletableFuture(qdrantClient.queryAsync(queryRequest));
            futures.add(future);
        }

        Map<String, ScoredResult> deduplicated = new LinkedHashMap<>();
        List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String collection = collectionNames.get(i);
            try {
                List<ScoredPoint> points = futures.get(i).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                mergePoints(points, collection, deduplicated);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                log.warn("[QDRANT] Search interrupted for collection={}", collection);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, "Interrupted", "Hybrid query was interrupted"));
            } catch (ExecutionException executionException) {
                Throwable cause = executionException.getCause();
                String exceptionType = cause == null
                        ? executionException.getClass().getSimpleName()
                        : cause.getClass().getSimpleName();
                String failureDetails = cause == null ? executionException.getMessage() : cause.getMessage();
                log.warn("[QDRANT] Search failed for collection={} (exceptionType={})", collection, exceptionType);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, exceptionType, sanitizeFailureDetails(failureDetails)));
            } catch (TimeoutException _) {
                log.warn("[QDRANT] Search timed out for collection={}", collection);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collection, "Timeout", "Hybrid query exceeded timeout " + timeout.toMillis() + "ms"));
            }
        }

        if (!collectionFailures.isEmpty() && failOnPartialSearchError) {
            throw new HybridSearchPartialFailureException(
                    "Hybrid retrieval failed for " + collectionFailures.size() + " collection(s)", collectionFailures);
        }

        List<Document> topDocuments = deduplicated.values().stream()
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(topK)
                .map(this::toDocument)
                .toList();
        List<HybridSearchNotice> retrievalNotices =
                collectionFailures.stream().map(HybridSearchService::toNotice).toList();
        return new SearchOutcome(topDocuments, retrievalNotices);
    }

    private QueryPoints buildHybridQueryRequest(
            String collection,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            String denseVectorName,
            String sparseVectorName,
            int prefetchLimit,
            int limit,
            Optional<Filter> retrievalFilter,
            int rrfK) {

        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(limit);
        retrievalFilter.ifPresent(builder::setFilter);

        PrefetchQuery.Builder densePrefetchBuilder = PrefetchQuery.newBuilder()
                .setQuery(nearest(Objects.requireNonNull(denseVector, "denseVector")))
                .setUsing(denseVectorName)
                .setLimit(prefetchLimit);
        retrievalFilter.ifPresent(densePrefetchBuilder::setFilter);
        builder.addPrefetch(densePrefetchBuilder.build());

        if (!sparseVector.indices().isEmpty()) {
            List<Integer> intIndices =
                    sparseVector.indices().stream().map(Long::intValue).toList();
            PrefetchQuery.Builder sparsePrefetchBuilder = PrefetchQuery.newBuilder()
                    .setQuery(nearest(vectorInput(sparseVector.values(), intIndices)))
                    .setUsing(sparseVectorName)
                    .setLimit(prefetchLimit);
            retrievalFilter.ifPresent(sparsePrefetchBuilder::setFilter);
            builder.addPrefetch(sparsePrefetchBuilder.build());
        }

        builder.setQuery(rrf(Rrf.newBuilder().setK(rrfK).build()));
        return builder.build();
    }

    private static void mergePoints(
            List<ScoredPoint> points, String collection, Map<String, ScoredResult> deduplicated) {
        for (ScoredPoint point : points) {
            String pointId = extractPointId(point);
            ScoredResult existing = deduplicated.get(pointId);
            if (existing == null || point.getScore() > existing.score()) {
                deduplicated.put(pointId, new ScoredResult(pointId, point.getScore(), point, collection));
            }
        }
    }

    private List<String> allCollectionNames() {
        AppProperties.QdrantCollections collections = appProperties.getQdrant().getCollections();
        return List.of(collections.getBooks(), collections.getDocs(), collections.getArticles(), collections.getPdfs());
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
        putIfPresentString(payload, target, "url");
        putIfPresentString(payload, target, "title");
        putIfPresentString(payload, target, "package");
        putIfPresentString(payload, target, "hash");
        putIfPresentString(payload, target, "docSet");
        putIfPresentString(payload, target, "docPath");
        putIfPresentString(payload, target, "sourceName");
        putIfPresentString(payload, target, "sourceKind");
        putIfPresentString(payload, target, "docVersion");
        putIfPresentString(payload, target, "docType");
        putIfPresentInteger(payload, target, "chunkIndex");
        putIfPresentInteger(payload, target, "pageStart");
        putIfPresentInteger(payload, target, "pageEnd");
    }

    private static void putIfPresentString(Map<String, Value> payload, Document target, String key) {
        String value = extractPayloadString(payload, key);
        if (!value.isBlank()) {
            target.getMetadata().put(key, value);
        }
    }

    private static void putIfPresentInteger(Map<String, Value> payload, Document target, String key) {
        Integer value = extractPayloadInteger(payload, key);
        if (value != null) {
            target.getMetadata().put(key, value);
        }
    }

    private static String extractPayloadString(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        Value value = payload.get(key);
        if (value == null) {
            return "";
        }
        return value.getKindCase() == Value.KindCase.STRING_VALUE
                ? value.getStringValue()
                : String.valueOf(fromValue(value));
    }

    private static Integer extractPayloadInteger(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Value value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value.getKindCase() == Value.KindCase.INTEGER_VALUE) {
            long l = value.getIntegerValue();
            if (l > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (l < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return (int) l;
        }
        Object maybe = fromValue(value);
        if (maybe instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static Object fromValue(Value value) {
        if (value == null) {
            return null;
        }
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE -> null;
            default -> null;
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
