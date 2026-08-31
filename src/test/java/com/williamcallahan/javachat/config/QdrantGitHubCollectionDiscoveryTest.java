package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.williamcallahan.javachat.service.EmbeddingClient;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** Verifies shared-generation discovery and fail-closed GitHub schema validation. */
class QdrantGitHubCollectionDiscoveryTest {
    private static final String GENERATION_PREFIX = "github-qwen3-embedding-4b-2560-";
    private static final int EMBEDDING_DIMENSIONS = 2_560;

    @Test
    void discoversOnlyExactGenerationPrefixWhenSchemaIsValid() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        AppProperties appProperties = new AppProperties();
        String activeCollection = GENERATION_PREFIX + "openai-java-chat";
        SettableFuture<java.util.List<String>> collectionNames = SettableFuture.create();
        collectionNames.set(java.util.List.of(
                activeCollection,
                "github-prod-qwen3-embedding-4b-2560-openai-java-chat",
                "github-dev-openai-java-chat"));
        SettableFuture<CollectionInfo> collectionInfo = SettableFuture.create();
        collectionInfo.set(validCollectionInfo(EMBEDDING_DIMENSIONS));
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class))).thenReturn(collectionNames);
        when(qdrantClient.getCollectionInfoAsync(activeCollection)).thenReturn(collectionInfo);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, appProperties);
        discovery.discoverGitHubCollections();

        assertEquals(java.util.List.of(activeCollection), discovery.getDiscoveredCollections());
        assertEquals(Status.UP, discovery.discoveryHealth().getStatus());
    }

    @Test
    void schemaMismatchMarksDiscoveryFailedAndPublishesNoCollections() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        String activeCollection = GENERATION_PREFIX + "openai-java-chat";
        SettableFuture<java.util.List<String>> collectionNames = SettableFuture.create();
        collectionNames.set(java.util.List.of(activeCollection));
        SettableFuture<CollectionInfo> collectionInfo = SettableFuture.create();
        collectionInfo.set(validCollectionInfo(4_096));
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class))).thenReturn(collectionNames);
        when(qdrantClient.getCollectionInfoAsync(activeCollection)).thenReturn(collectionInfo);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());
        discovery.discoverGitHubCollections();

        assertEquals(java.util.List.of(), discovery.getDiscoveredCollections());
        assertEquals(Status.DOWN, discovery.discoveryHealth().getStatus());
        assertEquals("failed", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    @Test
    void transientQdrantFailureRemainsPendingAndRecoversOnRetry() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        SettableFuture<java.util.List<String>> unavailableCollectionNames = SettableFuture.create();
        unavailableCollectionNames.setException(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));
        SettableFuture<java.util.List<String>> recoveredCollectionNames = SettableFuture.create();
        recoveredCollectionNames.set(java.util.List.of());
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class)))
                .thenReturn(unavailableCollectionNames)
                .thenReturn(recoveredCollectionNames);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());

        discovery.discoverGitHubCollections();
        assertEquals(Status.DOWN, discovery.discoveryHealth().getStatus());
        assertEquals("pending", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));

        discovery.retryPendingDiscovery();
        assertEquals(Status.UP, discovery.discoveryHealth().getStatus());
        assertEquals("ready", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    @Test
    void refreshIncludesCollectionsCreatedAfterStartup() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        String newCollection = GENERATION_PREFIX + "new-repository";
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class)))
                .thenReturn(Futures.immediateFuture(java.util.List.of()))
                .thenReturn(Futures.immediateFuture(java.util.List.of(newCollection)));
        when(qdrantClient.getCollectionInfoAsync(newCollection))
                .thenReturn(Futures.immediateFuture(validCollectionInfo(EMBEDDING_DIMENSIONS)));
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());
        discovery.discoverGitHubCollections();

        assertEquals(java.util.List.of(newCollection), discovery.refreshDiscoveredCollections());
        assertEquals(java.util.List.of(), discovery.getDiscoveredCollections());
        assertEquals("ready", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    @Test
    void inventoryRefreshReportsRemovalWithoutMutatingRetrievalSnapshot() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        String existingCollection = GENERATION_PREFIX + "existing-repository";
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class)))
                .thenReturn(Futures.immediateFuture(java.util.List.of(existingCollection)))
                .thenReturn(Futures.immediateFuture(java.util.List.of()));
        when(qdrantClient.getCollectionInfoAsync(existingCollection))
                .thenReturn(Futures.immediateFuture(validCollectionInfo(EMBEDDING_DIMENSIONS)));
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());
        discovery.discoverGitHubCollections();

        assertEquals(java.util.List.of(), discovery.refreshDiscoveredCollections());
        assertEquals(java.util.List.of(existingCollection), discovery.getDiscoveredCollections());
        assertEquals("ready", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    @Test
    void discoveryCancelsOutstandingValidationAfterSchemaFailure() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        String invalidCollection = GENERATION_PREFIX + "invalid-repository";
        String pendingCollection = GENERATION_PREFIX + "pending-repository";
        SettableFuture<CollectionInfo> pendingCollectionInfo = SettableFuture.create();
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class)))
                .thenReturn(Futures.immediateFuture(java.util.List.of(invalidCollection, pendingCollection)));
        when(qdrantClient.getCollectionInfoAsync(invalidCollection))
                .thenReturn(Futures.immediateFuture(validCollectionInfo(EMBEDDING_DIMENSIONS - 1)));
        when(qdrantClient.getCollectionInfoAsync(pendingCollection)).thenReturn(pendingCollectionInfo);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());
        discovery.discoverGitHubCollections();

        assertTrue(pendingCollectionInfo.isCancelled());
        assertEquals("failed", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    @Test
    void discoveryCancelsCollectionListingAfterLocalTimeout()
            throws InterruptedException, ExecutionException, TimeoutException {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ListenableFuture<java.util.List<String>> collectionNamesRequest = mock();
        when(qdrantClient.listCollectionsAsync(any(java.time.Duration.class))).thenReturn(collectionNamesRequest);
        when(collectionNamesRequest.get(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(new TimeoutException("stalled"));
        when(collectionNamesRequest.isDone()).thenReturn(false);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties());
        discovery.discoverGitHubCollections();

        verify(collectionNamesRequest).cancel(true);
        assertEquals("pending", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
    }

    private static CollectionInfo validCollectionInfo(int denseDimensions) {
        VectorParamsMap vectorParams = VectorParamsMap.newBuilder()
                .putMap(
                        "dense",
                        VectorParams.newBuilder()
                                .setSize(denseDimensions)
                                .setDistance(Distance.Cosine)
                                .build())
                .build();
        SparseVectorConfig sparseVectorConfig = SparseVectorConfig.newBuilder()
                .putMap(
                        "bm25",
                        SparseVectorParams.newBuilder()
                                .setModifier(Modifier.Idf)
                                .build())
                .build();
        CollectionParams collectionParams = CollectionParams.newBuilder()
                .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(vectorParams))
                .setSparseVectorsConfig(sparseVectorConfig)
                .setOnDiskPayload(true)
                .build();
        CollectionInfo.Builder collectionInfo = CollectionInfo.newBuilder()
                .setConfig(CollectionConfig.newBuilder().setParams(collectionParams));
        QdrantGitHubCollectionDiscovery.requiredPayloadIndexes()
                .forEach((fieldName, schemaType) -> collectionInfo.putPayloadSchema(
                        fieldName,
                        PayloadSchemaInfo.newBuilder()
                                .setDataType(schemaType)
                                .setParams(PayloadIndexParams.getDefaultInstance())
                                .build()));
        return collectionInfo.build();
    }
}
