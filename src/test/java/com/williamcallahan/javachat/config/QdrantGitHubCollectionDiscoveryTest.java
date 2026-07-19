package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** Verifies exact environment/generation discovery and fail-closed GitHub schema validation. */
class QdrantGitHubCollectionDiscoveryTest {
    private static final String DEV_PREFIX = "github-dev-qwen3-embedding-4b-2560-";
    private static final int EMBEDDING_DIMENSIONS = 2_560;

    @Test
    void rejectsUnknownDeploymentProfileBeforeDiscovery() {
        assertThrows(
                IllegalStateException.class,
                () -> new QdrantGitHubCollectionDiscovery(
                        mock(QdrantClient.class), mock(EmbeddingClient.class), new AppProperties(), "staging"));
    }

    @Test
    void discoversOnlyExactActivePrefixWhenSchemaIsValid() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        AppProperties appProperties = new AppProperties();
        String activeCollection = DEV_PREFIX + "openai-java-chat";
        SettableFuture<java.util.List<String>> collectionNames = SettableFuture.create();
        collectionNames.set(java.util.List.of(
                activeCollection,
                "github-prod-qwen3-embedding-4b-2560-openai-java-chat",
                "github-dev-openai-java-chat"));
        SettableFuture<CollectionInfo> collectionInfo = SettableFuture.create();
        collectionInfo.set(validCollectionInfo(EMBEDDING_DIMENSIONS));
        when(qdrantClient.listCollectionsAsync()).thenReturn(collectionNames);
        when(qdrantClient.getCollectionInfoAsync(activeCollection)).thenReturn(collectionInfo);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, appProperties, "dev");
        discovery.discoverGitHubCollections();

        assertEquals(java.util.List.of(activeCollection), discovery.getDiscoveredCollections());
        assertEquals(Status.UP, discovery.discoveryHealth().getStatus());
    }

    @Test
    void schemaMismatchMarksDiscoveryFailedAndPublishesNoCollections() {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        String activeCollection = DEV_PREFIX + "openai-java-chat";
        SettableFuture<java.util.List<String>> collectionNames = SettableFuture.create();
        collectionNames.set(java.util.List.of(activeCollection));
        SettableFuture<CollectionInfo> collectionInfo = SettableFuture.create();
        collectionInfo.set(validCollectionInfo(4_096));
        when(qdrantClient.listCollectionsAsync()).thenReturn(collectionNames);
        when(qdrantClient.getCollectionInfoAsync(activeCollection)).thenReturn(collectionInfo);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties(), "dev");
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
        when(qdrantClient.listCollectionsAsync())
                .thenReturn(unavailableCollectionNames)
                .thenReturn(recoveredCollectionNames);

        QdrantGitHubCollectionDiscovery discovery =
                new QdrantGitHubCollectionDiscovery(qdrantClient, embeddingClient, new AppProperties(), "dev");

        discovery.discoverGitHubCollections();
        assertEquals(Status.DOWN, discovery.discoveryHealth().getStatus());
        assertEquals("pending", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));

        discovery.retryPendingDiscovery();
        assertEquals(Status.UP, discovery.discoveryHealth().getStatus());
        assertEquals("ready", discovery.discoveryHealth().getDetails().get("githubCollectionDiscovery"));
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
