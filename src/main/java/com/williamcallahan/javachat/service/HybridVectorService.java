package com.williamcallahan.javachat.service;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.support.RetrySupport;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Upserts documents into Qdrant with both dense and sparse named vectors in a single write.
 *
 * <p>This replaces the two-step pattern (dense upsert + sparse update) with a single
 * gRPC upsert containing named vectors for both the configured dense and sparse vector names.</p>
 *
 * <p>Verified API contract (Step 0): this adapter writes directly through {@code io.qdrant:client}
 * 1.16.2 and relies on named vectors ({@code VectorsFactory.namedVectors}) where dense/sparse
 * names must exactly match collection schema keys provisioned at startup.</p>
 */
@Service
public class HybridVectorService {
    private static final Logger log = LoggerFactory.getLogger(HybridVectorService.class);

    private static final long UPSERT_TIMEOUT_SECONDS = 30;
    private static final long DELETE_TIMEOUT_SECONDS = 15;
    private static final long COUNT_TIMEOUT_SECONDS = 15;
    private static final String PAYLOAD_DOC_CONTENT = "doc_content";

    private static final Set<String> SUPPORTED_METADATA_KEYS = Set.of(
            "url",
            "title",
            "chunkIndex",
            "package",
            "hash",
            "docSet",
            "docPath",
            "sourceName",
            "sourceKind",
            "docVersion",
            "docType",
            "pageStart",
            "pageEnd");

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;
    private final LexicalSparseVectorEncoder sparseVectorEncoder;
    private final AppProperties appProperties;

    /**
     * Wires gRPC client and embedding dependencies for hybrid upserts.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param embeddingClient embedding client for dense vectors
     * @param sparseVectorEncoder sparse encoder for lexical vectors
     * @param appProperties application configuration
     */
    public HybridVectorService(
            QdrantClient qdrantClient,
            EmbeddingClient embeddingClient,
            LexicalSparseVectorEncoder sparseVectorEncoder,
            AppProperties appProperties) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.sparseVectorEncoder = Objects.requireNonNull(sparseVectorEncoder, "sparseVectorEncoder");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
    }

    /**
     * Upserts documents with both dense and sparse vectors to the selected collection.
     *
     * @param collectionKind target collection kind
     * @param documents Spring AI documents with metadata and text
     */
    public void upsert(QdrantCollectionKind collectionKind, List<Document> documents) {
        Objects.requireNonNull(collectionKind, "collectionKind");
        Objects.requireNonNull(documents, "documents");
        if (documents.isEmpty()) {
            return;
        }

        String collectionName = resolveCollectionName(collectionKind);
        String denseVectorName = appProperties.getQdrant().getDenseVectorName();
        String sparseVectorName = appProperties.getQdrant().getSparseVectorName();

        List<String> texts = documents.stream()
                .map(document -> {
                    String text = document.getText();
                    return text == null ? "" : text;
                })
                .toList();
        List<float[]> embeddings = embeddingClient.embed(texts);

        List<PointStruct> points = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String pointId = resolvePointId(doc);
            float[] denseVector = embeddings.get(i);
            LexicalSparseVectorEncoder.SparseVector sparse = sparseVectorEncoder.encode(doc.getText());

            PointStruct point = buildPoint(pointId, denseVector, sparse, denseVectorName, sparseVectorName, doc);
            points.add(point);
        }

        RetrySupport.executeWithRetry(
                () -> {
                    awaitFuture(qdrantClient.upsertAsync(collectionName, points), UPSERT_TIMEOUT_SECONDS);
                    return null;
                },
                "Qdrant hybrid upsert");

        log.info("[QDRANT] Upserted {} hybrid points", points.size());
    }

    /**
     * Deletes points matching a URL filter from the specified collection.
     *
     * @param collectionKind target collection kind
     * @param url URL to match for deletion
     */
    public void deleteByUrl(QdrantCollectionKind collectionKind, String url) {
        Objects.requireNonNull(collectionKind, "collectionKind");
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            return;
        }

        String collectionName = resolveCollectionName(collectionKind);
        Filter filter = Filter.newBuilder().addMust(matchKeyword("url", url)).build();

        RetrySupport.executeWithRetry(
                () -> {
                    awaitFuture(qdrantClient.deleteAsync(collectionName, filter), DELETE_TIMEOUT_SECONDS);
                    return null;
                },
                "Qdrant delete by URL");

        log.debug("[QDRANT] Deleted points by URL filter");
    }

    /**
     * Returns true when at least one point exists for the URL in the given collection.
     *
     * @param collectionKind target collection kind
     * @param url URL payload value to match
     * @return true when one or more points exist
     */
    public boolean hasPointsForUrl(QdrantCollectionKind collectionKind, String url) {
        Objects.requireNonNull(collectionKind, "collectionKind");
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            return false;
        }

        String collectionName = resolveCollectionName(collectionKind);
        Filter filter = Filter.newBuilder().addMust(matchKeyword("url", url)).build();

        Long count = RetrySupport.executeWithRetry(
                () -> awaitFuture(qdrantClient.countAsync(collectionName, filter, true), COUNT_TIMEOUT_SECONDS),
                "Qdrant count by URL");
        return count != null && count > 0;
    }

    /**
     * Returns the Qdrant collection name for the given kind.
     *
     * <p>Collection names are configured in {@code app.qdrant.collections.*}.</p>
     */
    public String resolveCollectionName(QdrantCollectionKind kind) {
        Objects.requireNonNull(kind, "kind");
        AppProperties.QdrantCollections collections = appProperties.getQdrant().getCollections();
        return switch (kind) {
            case BOOKS -> collections.getBooks();
            case DOCS -> collections.getDocs();
            case ARTICLES -> collections.getArticles();
            case PDFS -> collections.getPdfs();
        };
    }

    private PointStruct buildPoint(
            String pointId,
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparse,
            String denseVectorName,
            String sparseVectorName,
            Document doc) {

        Map<String, Vector> namedVectorMap = new LinkedHashMap<>(2);
        namedVectorMap.put(denseVectorName, vector(Objects.requireNonNull(denseVector, "denseVector")));
        if (!sparse.indices().isEmpty()) {
            List<Integer> intIndices =
                    sparse.indices().stream().map(Long::intValue).toList();
            namedVectorMap.put(
                    sparseVectorName, vector(sparse.values(), Objects.requireNonNull(intIndices, "intIndices")));
        }

        Map<String, Value> payload = buildPayload(doc);

        return PointStruct.newBuilder()
                .setId(id(UUID.fromString(pointId)))
                .setVectors(namedVectors(namedVectorMap))
                .putAllPayload(payload)
                .build();
    }

    private Map<String, Value> buildPayload(Document doc) {
        var payload = new LinkedHashMap<String, Value>();
        String documentText = doc.getText();
        payload.put(PAYLOAD_DOC_CONTENT, io.qdrant.client.ValueFactory.value(documentText == null ? "" : documentText));

        var metadata = doc.getMetadata();
        for (String key : SUPPORTED_METADATA_KEYS) {
            Object raw = metadata.get(key);
            Value value = toValue(raw);
            if (value != null) {
                payload.put(key, value);
            }
        }

        return payload;
    }

    private static Value toValue(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String s) {
            return s.isBlank() ? null : io.qdrant.client.ValueFactory.value(s);
        }
        if (obj instanceof Integer i) {
            return io.qdrant.client.ValueFactory.value(i.longValue());
        }
        if (obj instanceof Long l) {
            return io.qdrant.client.ValueFactory.value(l);
        }
        if (obj instanceof Double d) {
            return io.qdrant.client.ValueFactory.value(d);
        }
        if (obj instanceof Float f) {
            return io.qdrant.client.ValueFactory.value(f.doubleValue());
        }
        if (obj instanceof Boolean b) {
            return io.qdrant.client.ValueFactory.value(b);
        }
        if (obj instanceof Number n) {
            return io.qdrant.client.ValueFactory.value(n.doubleValue());
        }
        return io.qdrant.client.ValueFactory.value(String.valueOf(obj));
    }

    private static String resolvePointId(Document doc) {
        String docId = Objects.requireNonNull(doc.getId(), "doc.id");
        if (!docId.isBlank()) {
            return docId;
        }
        return UUID.randomUUID().toString();
    }

    private static <T> T awaitFuture(
            com.google.common.util.concurrent.ListenableFuture<T> future, long timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant operation interrupted", interrupted);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause == null) {
                throw new IllegalStateException("Qdrant operation failed", executionException);
            }
            throw new IllegalStateException("Qdrant operation failed", cause);
        } catch (TimeoutException timeoutException) {
            throw new IllegalStateException(
                    "Qdrant operation timed out after " + timeoutSeconds + "s", timeoutException);
        }
    }
}
