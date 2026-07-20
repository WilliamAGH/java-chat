package com.williamcallahan.javachat.service;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantCollectionNames;
import com.williamcallahan.javachat.support.RetrySupport;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
 * 1.18.3 and relies on named vectors ({@code VectorsFactory.namedVectors}) where dense/sparse
 * names must exactly match collection schema keys provisioned at startup.</p>
 */
@Service
public class HybridVectorService {
    private static final Logger log = LoggerFactory.getLogger(HybridVectorService.class);

    private static final long UPSERT_TIMEOUT_SECONDS = 30;
    private static final long DELETE_TIMEOUT_SECONDS = 15;
    private static final long COUNT_TIMEOUT_SECONDS = 15;
    private static final long SCROLL_TIMEOUT_SECONDS = 30;
    private static final long SET_PAYLOAD_TIMEOUT_SECONDS = 30;
    private static final int SCROLL_PAGE_LIMIT = 256;
    private static final String NULL_MESSAGE_COLLECTION_KIND = "collectionKind";
    private static final String NULL_MESSAGE_COLLECTION_NAME = "collectionName";

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
        Objects.requireNonNull(collectionKind, NULL_MESSAGE_COLLECTION_KIND);
        String collectionName =
                Objects.requireNonNull(resolveCollectionName(collectionKind), NULL_MESSAGE_COLLECTION_NAME);
        doUpsert(collectionName, documents);
    }

    /**
     * Upserts documents to a collection identified by name rather than enum kind.
     *
     * <p>Used for dynamically created collections (e.g., GitHub repository collections)
     * that are not part of the fixed {@link QdrantCollectionKind} enum.</p>
     *
     * @param collectionName target Qdrant collection name
     * @param documents Spring AI documents with metadata and text
     */
    public void upsertToCollection(String collectionName, List<Document> documents) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        doUpsert(collectionName, documents);
    }

    /**
     * Replaces every point for one URL without removing the prior complete page before its successor exists.
     *
     * <p>The existing point identities are captured first, the complete replacement is then upserted with
     * Qdrant's waited write contract, and only identities absent from the replacement are deleted. An upsert
     * failure therefore leaves the prior page intact; a later deletion failure leaves duplicate stale chunks
     * rather than an absent page.</p>
     *
     * @param collectionKind target collection kind
     * @param sourceUrl URL whose indexed page is being replaced
     * @param replacementDocuments complete replacement document set with stable UUID identities
     */
    public void replaceUrlDocuments(
            QdrantCollectionKind collectionKind, String sourceUrl, List<Document> replacementDocuments) {
        Objects.requireNonNull(collectionKind, NULL_MESSAGE_COLLECTION_KIND);
        String collectionName =
                Objects.requireNonNull(resolveCollectionName(collectionKind), NULL_MESSAGE_COLLECTION_NAME);
        replaceUrlDocuments(collectionName, sourceUrl, replacementDocuments);
    }

    /**
     * Replaces every point for one URL in a dynamically named collection without deleting the prior page first.
     *
     * @param collectionName target Qdrant collection name
     * @param sourceUrl URL whose indexed page is being replaced
     * @param replacementDocuments complete replacement document set with stable UUID identities
     */
    public void replaceUrlDocuments(String collectionName, String sourceUrl, List<Document> replacementDocuments) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        String requiredSourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
        if (requiredSourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        List<Document> requiredReplacementDocuments =
                Objects.requireNonNull(replacementDocuments, "replacementDocuments");
        if (requiredReplacementDocuments.isEmpty()) {
            throw new IllegalArgumentException("replacementDocuments must not be empty");
        }
        requireReplacementSourceUrl(requiredSourceUrl, requiredReplacementDocuments);

        Set<PointId> replacementPointIds = replacementPointIds(requiredReplacementDocuments);
        Set<PointId> priorPointIds = scrollPointIdsForUrl(collectionName, requiredSourceUrl);

        doUpsert(collectionName, requiredReplacementDocuments);

        priorPointIds.removeAll(replacementPointIds);
        deletePointIds(collectionName, priorPointIds);
        Set<PointId> storedPointIds = scrollPointIdsForUrl(collectionName, requiredSourceUrl);
        if (!storedPointIds.equals(replacementPointIds)) {
            throw new IllegalStateException("URL replacement point identity mismatch: expected "
                    + replacementPointIds
                    + " but found "
                    + storedPointIds);
        }
    }

    /**
     * Deletes points matching a URL filter from the specified collection.
     *
     * @param collectionKind target collection kind
     * @param url URL to match for deletion
     */
    public void deleteByUrl(QdrantCollectionKind collectionKind, String url) {
        Objects.requireNonNull(collectionKind, NULL_MESSAGE_COLLECTION_KIND);
        doDeleteByUrl(resolveCollectionName(collectionKind), url);
    }

    /**
     * Deletes points matching a URL filter from a collection identified by name.
     *
     * @param collectionName target Qdrant collection name
     * @param url URL to match for deletion
     */
    public void deleteByUrl(String collectionName, String url) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        doDeleteByUrl(collectionName, url);
    }

    /**
     * Returns true when at least one point exists for the URL in the given collection.
     *
     * @param collectionKind target collection kind
     * @param url URL payload value to match
     * @return true when one or more points exist
     */
    public boolean hasPointsForUrl(QdrantCollectionKind collectionKind, String url) {
        return countPointsForUrl(collectionKind, url) > 0;
    }

    /**
     * Returns the exact number of points that match a URL in the given collection.
     *
     * @param collectionKind target collection kind
     * @param url URL payload value to match
     * @return exact point count for the URL
     */
    public long countPointsForUrl(QdrantCollectionKind collectionKind, String url) {
        Objects.requireNonNull(collectionKind, NULL_MESSAGE_COLLECTION_KIND);
        return doCountPointsForUrl(resolveCollectionName(collectionKind), url);
    }

    /**
     * Returns the exact number of points that match a URL in a collection identified by name.
     *
     * @param collectionName target Qdrant collection name
     * @param url URL payload value to match
     * @return exact point count for the URL
     */
    public long countPointsForUrl(String collectionName, String url) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        return doCountPointsForUrl(collectionName, url);
    }

    /**
     * Returns whether one URL owns exactly the expected deterministic document point UUIDs.
     *
     * <p>Count equality is insufficient because two page revisions can produce the same number of chunks
     * while owning disjoint point identities. This read compares the complete URL-filtered Qdrant identity
     * set with the point UUIDs derived from the expected chunk hashes.</p>
     *
     * @param collectionKind target collection kind
     * @param sourceUrl URL payload value that scopes the Qdrant scroll
     * @param expectedDocumentPointUuids deterministic document UUID strings expected for the URL
     * @return true only when Qdrant contains every expected UUID and no other UUID for the URL
     */
    public boolean hasExactPointIdsForUrl(
            QdrantCollectionKind collectionKind, String sourceUrl, List<String> expectedDocumentPointUuids) {
        Objects.requireNonNull(collectionKind, NULL_MESSAGE_COLLECTION_KIND);
        String requiredSourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
        if (requiredSourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        String collectionName =
                Objects.requireNonNull(resolveCollectionName(collectionKind), NULL_MESSAGE_COLLECTION_NAME);
        Set<PointId> expectedPointIds = documentPointIds(expectedDocumentPointUuids);
        return scrollPointIdsForUrl(collectionName, requiredSourceUrl).equals(expectedPointIds);
    }

    /**
     * Scrolls all unique URL payload values from points in a collection.
     *
     * <p>Uses selective payload retrieval ({@code url} field only) and disables vector
     * transfer to minimize network overhead. Paginates through the entire collection
     * using {@code ScrollPoints} with offset-based cursoring.</p>
     *
     * @param collectionName target Qdrant collection name
     * @return all unique URL values found across points in the collection
     */
    public Set<String> scrollAllUrlsInCollection(String collectionName) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }

        Set<String> collectedUrls = new LinkedHashSet<>();
        List<String> urlPayloadFields =
                Objects.requireNonNull(List.of(QdrantPayloadFieldSchema.URL_FIELD), "urlPayloadFields");
        ScrollPoints.Builder scrollRequestBuilder = ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setWithPayload(WithPayloadSelectorFactory.include(urlPayloadFields))
                .setWithVectors(WithVectorsSelectorFactory.enable(false))
                .setLimit(SCROLL_PAGE_LIMIT);

        while (true) {
            ScrollPoints scrollRequest = scrollRequestBuilder.build();
            ScrollResponse scrollResponse = RetrySupport.executeWithRetry(
                    () -> QdrantFutureAwaiter.awaitFuture(
                            qdrantClient.scrollAsync(Objects.requireNonNull(scrollRequest)), SCROLL_TIMEOUT_SECONDS),
                    "Qdrant scroll URLs");

            for (RetrievedPoint retrievedPoint : scrollResponse.getResultList()) {
                Value urlPayloadValue = retrievedPoint.getPayloadMap().get(QdrantPayloadFieldSchema.URL_FIELD);
                if (urlPayloadValue != null && urlPayloadValue.hasStringValue()) {
                    String urlString = urlPayloadValue.getStringValue();
                    if (!urlString.isBlank()) {
                        collectedUrls.add(urlString);
                    }
                }
            }

            if (!scrollResponse.hasNextPageOffset()) {
                break;
            }
            scrollRequestBuilder.setOffset(scrollResponse.getNextPageOffset());
        }

        log.debug("[QDRANT] Scrolled {} unique URLs from collection '{}'", collectedUrls.size(), collectionName);
        return collectedUrls;
    }

    /**
     * Updates payload fields on all points matching a filter without re-embedding.
     *
     * <p>Uses Qdrant's {@code setPayloadAsync} which performs a partial merge — only the
     * specified fields are modified; existing payload fields and vectors are untouched.</p>
     *
     * @param collectionName target Qdrant collection name
     * @param metadataFieldUpdates map of payload field names to new values
     * @param pointFilter filter selecting which points to update
     */
    public void updatePayloadByFilter(
            String collectionName, Map<String, Value> metadataFieldUpdates, Filter pointFilter) {
        Objects.requireNonNull(collectionName, NULL_MESSAGE_COLLECTION_NAME);
        Objects.requireNonNull(metadataFieldUpdates, "metadataFieldUpdates");
        Objects.requireNonNull(pointFilter, "pointFilter");
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        if (metadataFieldUpdates.isEmpty()) {
            return;
        }

        RetrySupport.executeWithRetry(
                () -> QdrantFutureAwaiter.awaitFuture(
                        qdrantClient.setPayloadAsync(
                                collectionName, metadataFieldUpdates, pointFilter, true, null, null),
                        SET_PAYLOAD_TIMEOUT_SECONDS),
                "Qdrant set payload by filter");

        log.debug(
                "[QDRANT] Updated {} payload field(s) on points matching filter in '{}'",
                metadataFieldUpdates.size(),
                collectionName);
    }

    /**
     * Returns the Qdrant collection name for the given kind.
     *
     * <p>Collection names are configured in {@code app.qdrant.collections.*}.</p>
     */
    public String resolveCollectionName(QdrantCollectionKind kind) {
        Objects.requireNonNull(kind, "kind");
        QdrantCollectionNames collections = appProperties.getQdrant().getCollections();
        return switch (kind) {
            case BOOKS -> collections.getBooks();
            case DOCS -> collections.getDocs();
            case ARTICLES -> collections.getArticles();
            case PDFS -> collections.getPdfs();
        };
    }

    private void doUpsert(String collectionName, List<Document> documents) {
        Objects.requireNonNull(documents, "documents");
        if (documents.isEmpty()) {
            return;
        }

        String denseVectorName = appProperties.getQdrant().getDenseVectorName();
        String sparseVectorName = appProperties.getQdrant().getSparseVectorName();

        List<float[]> embeddings =
                Objects.requireNonNull(EmbeddingBatchEmbedder.embedDocuments(embeddingClient, documents), "embeddings");
        if (embeddings.size() != documents.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch: expected " + documents.size() + " but received " + embeddings.size());
        }

        List<io.qdrant.client.grpc.Points.PointStruct> points = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document document = Objects.requireNonNull(documents.get(i), "documents[" + i + "]");
            float[] denseEmbedding = Objects.requireNonNull(embeddings.get(i), "embeddings[" + i + "]");
            String pointId = HybridVectorPointFactory.resolvePointId(document);
            HybridVectorPointFactory.HybridVectorSet vectorSet = new HybridVectorPointFactory.HybridVectorSet(
                    denseEmbedding, sparseVectorEncoder.encode(document.getText()), denseVectorName, sparseVectorName);

            io.qdrant.client.grpc.Points.PointStruct point =
                    HybridVectorPointFactory.buildPoint(pointId, vectorSet, document);
            points.add(point);
        }

        RetrySupport.executeWithRetry(
                () -> QdrantFutureAwaiter.awaitFuture(
                        qdrantClient.upsertAsync(Objects.requireNonNull(collectionName), points),
                        UPSERT_TIMEOUT_SECONDS),
                "Qdrant hybrid upsert");

        log.info("[QDRANT] Upserted {} hybrid points", points.size());
    }

    private void doDeleteByUrl(String collectionName, String url) {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            return;
        }

        Filter filter = Filter.newBuilder()
                .addMust(matchKeyword(QdrantPayloadFieldSchema.URL_FIELD, url))
                .build();

        RetrySupport.executeWithRetry(
                () -> QdrantFutureAwaiter.awaitFuture(
                        qdrantClient.deleteAsync(
                                Objects.requireNonNull(collectionName), Objects.requireNonNull(filter)),
                        DELETE_TIMEOUT_SECONDS),
                "Qdrant delete by URL");

        log.debug("[QDRANT] Deleted points by URL filter");
    }

    private Set<PointId> scrollPointIdsForUrl(String collectionName, String sourceUrl) {
        Filter sourceUrlFilter = Filter.newBuilder()
                .addMust(matchKeyword(QdrantPayloadFieldSchema.URL_FIELD, sourceUrl))
                .build();
        ScrollPoints.Builder scrollRequestBuilder = ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setFilter(sourceUrlFilter)
                .setWithPayload(WithPayloadSelectorFactory.enable(false))
                .setWithVectors(WithVectorsSelectorFactory.enable(false))
                .setLimit(SCROLL_PAGE_LIMIT);
        Set<PointId> pointIds = new LinkedHashSet<>();

        while (true) {
            ScrollPoints scrollRequest = scrollRequestBuilder.build();
            ScrollResponse scrollResponse = RetrySupport.executeWithRetry(
                    () -> QdrantFutureAwaiter.awaitFuture(
                            qdrantClient.scrollAsync(Objects.requireNonNull(scrollRequest)), SCROLL_TIMEOUT_SECONDS),
                    "Qdrant scroll point IDs by URL");
            for (RetrievedPoint retrievedPoint : scrollResponse.getResultList()) {
                if (retrievedPoint.hasId()) {
                    pointIds.add(retrievedPoint.getId());
                }
            }
            if (!scrollResponse.hasNextPageOffset()) {
                return pointIds;
            }
            scrollRequestBuilder.setOffset(scrollResponse.getNextPageOffset());
        }
    }

    private static Set<PointId> replacementPointIds(List<Document> replacementDocuments) {
        List<String> replacementPointUuids = new ArrayList<>(replacementDocuments.size());
        for (int documentIndex = 0; documentIndex < replacementDocuments.size(); documentIndex++) {
            Document replacementDocument = Objects.requireNonNull(
                    replacementDocuments.get(documentIndex), "replacementDocuments[" + documentIndex + "]");
            String documentId = Objects.requireNonNull(replacementDocument.getId(), "replacementDocument.id");
            if (documentId.isBlank()) {
                throw new IllegalArgumentException("Replacement document identities must not be blank");
            }
            replacementPointUuids.add(documentId);
        }
        return documentPointIds(replacementPointUuids);
    }

    private static Set<PointId> documentPointIds(List<String> documentPointUuids) {
        List<String> requiredPointUuids = List.copyOf(Objects.requireNonNull(documentPointUuids, "documentPointUuids"));
        Set<PointId> pointIds = new LinkedHashSet<>();
        for (int pointIndex = 0; pointIndex < requiredPointUuids.size(); pointIndex++) {
            String pointUuid = Objects.requireNonNull(
                    requiredPointUuids.get(pointIndex), "documentPointUuids[" + pointIndex + "]");
            if (pointUuid.isBlank()) {
                throw new IllegalArgumentException("Document point UUIDs must not be blank");
            }
            if (!pointIds.add(id(UUID.fromString(pointUuid)))) {
                throw new IllegalArgumentException("Document point UUIDs must be unique");
            }
        }
        return pointIds;
    }

    private static void requireReplacementSourceUrl(String sourceUrl, List<Document> replacementDocuments) {
        for (int documentIndex = 0; documentIndex < replacementDocuments.size(); documentIndex++) {
            Document replacementDocument = Objects.requireNonNull(
                    replacementDocuments.get(documentIndex), "replacementDocuments[" + documentIndex + "]");
            String documentSourceUrl =
                    DocumentFactory.metadataText(replacementDocument, QdrantPayloadFieldSchema.URL_FIELD);
            if (!sourceUrl.equals(documentSourceUrl)) {
                throw new IllegalArgumentException("Every replacement document must use sourceUrl metadata");
            }
        }
    }

    private void deletePointIds(String collectionName, Set<PointId> obsoletePointIds) {
        if (obsoletePointIds.isEmpty()) {
            return;
        }
        List<PointId> pointIds = List.copyOf(obsoletePointIds);
        RetrySupport.executeWithRetry(
                () -> QdrantFutureAwaiter.awaitFuture(
                        qdrantClient.deleteAsync(Objects.requireNonNull(collectionName), pointIds),
                        DELETE_TIMEOUT_SECONDS),
                "Qdrant delete obsolete point IDs");
        log.debug("[QDRANT] Deleted {} obsolete points after URL replacement", pointIds.size());
    }

    private long doCountPointsForUrl(String collectionName, String url) {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            return 0;
        }

        Filter filter = Filter.newBuilder()
                .addMust(matchKeyword(QdrantPayloadFieldSchema.URL_FIELD, url))
                .build();

        Long count = RetrySupport.executeWithRetry(
                () -> QdrantFutureAwaiter.awaitFuture(
                        qdrantClient.countAsync(
                                Objects.requireNonNull(collectionName), Objects.requireNonNull(filter), true),
                        COUNT_TIMEOUT_SECONDS),
                "Qdrant count by URL");
        return count == null ? 0 : count;
    }
}
