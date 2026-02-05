package com.williamcallahan.javachat.service;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.VectorInputFactory.vectorInput;

import com.williamcallahan.javachat.config.AppProperties;
import io.qdrant.client.QdrantClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Executes hybrid (dense + sparse) search across all Qdrant collections using RRF fusion.
 *
 * <p>For each collection, a Qdrant Query API request is issued with two prefetch stages
 * (dense nearest-neighbor and sparse BM25-style lexical search) fused via reciprocal rank
 * fusion (RRF). Queries are fanned out to all configured collections in parallel and results
 * are deduplicated by point UUID before returning top-K.</p>
 */
@Service
public class HybridSearchService {
    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final String PAYLOAD_DOC_CONTENT = "doc_content";

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final LexicalSparseVectorEncoder sparseVectorEncoder;
    private final AppProperties appProperties;

    /**
     * Wires gRPC client and embedding dependencies for hybrid search.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param embeddingModel embedding model for dense query vectors
     * @param sparseVectorEncoder sparse encoder for lexical query vectors
     * @param appProperties application configuration
     */
    public HybridSearchService(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            LexicalSparseVectorEncoder sparseVectorEncoder,
            AppProperties appProperties) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.sparseVectorEncoder = Objects.requireNonNull(sparseVectorEncoder, "sparseVectorEncoder");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
    }

    /**
     * Performs hybrid search across all configured collections.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @return documents sorted by fused score (highest first)
     */
    public List<Document> search(String query, int topK) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank() || topK <= 0) {
            return List.of();
        }

        float[] denseVector = embeddingModel.embed(query);
        LexicalSparseVectorEncoder.SparseVector sparseVector = sparseVectorEncoder.encode(query);

        List<String> collectionNames = allCollectionNames();
        String denseVectorName = appProperties.getQdrant().getDenseVectorName();
        String sparseVectorName = appProperties.getQdrant().getSparseVectorName();
        int prefetchLimit = appProperties.getQdrant().getPrefetchLimit();
        Duration timeout = appProperties.getQdrant().getQueryTimeout();

        List<CompletableFuture<List<ScoredPoint>>> futures = new ArrayList<>(collectionNames.size());
        for (String collection : collectionNames) {
            QueryPoints queryRequest = buildHybridQueryRequest(
                    collection, denseVector, sparseVector, denseVectorName, sparseVectorName, prefetchLimit, topK);
            CompletableFuture<List<ScoredPoint>> future = toCompletableFuture(qdrantClient.queryAsync(queryRequest));
            futures.add(future);
        }

        Map<String, ScoredResult> deduplicated = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            String collection = collectionNames.get(i);
            try {
                List<ScoredPoint> points = futures.get(i).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                for (ScoredPoint point : points) {
                    String pointId = extractPointId(point);
                    ScoredResult existing = deduplicated.get(pointId);
                    if (existing == null || point.getScore() > existing.score()) {
                        deduplicated.put(pointId, new ScoredResult(pointId, point.getScore(), point, collection));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("[QDRANT] Search interrupted for collection {}", collection);
            } catch (ExecutionException executionException) {
                log.warn(
                        "[QDRANT] Search failed for collection {}: {}",
                        collection,
                        executionException.getCause() == null
                                ? executionException.getMessage()
                                : executionException.getCause().getMessage());
            } catch (TimeoutException timeoutException) {
                log.warn("[QDRANT] Search timed out for collection {}", collection);
            }
        }

        return deduplicated.values().stream()
                .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
                .limit(topK)
                .map(this::toDocument)
                .toList();
    }

    private QueryPoints buildHybridQueryRequest(
            String collection,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            String denseVectorName,
            String sparseVectorName,
            int prefetchLimit,
            int limit) {

        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                .setLimit(limit);

        PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(nearest(denseVector))
                .setUsing(denseVectorName)
                .setLimit(prefetchLimit)
                .build();
        builder.addPrefetch(densePrefetch);

        if (!sparseVector.indices().isEmpty()) {
            List<Integer> intIndices =
                    sparseVector.indices().stream().map(Long::intValue).toList();
            PrefetchQuery sparsePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(vectorInput(sparseVector.values(), intIndices)))
                    .setUsing(sparseVectorName)
                    .setLimit(prefetchLimit)
                    .build();
            builder.addPrefetch(sparsePrefetch);
        }

        builder.setQuery(rrf(Rrf.newBuilder().build()));
        return builder.build();
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

    private record ScoredResult(String id, double score, ScoredPoint point, String collection) {}
}
