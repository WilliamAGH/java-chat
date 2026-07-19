package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Verifies that Qdrant initialization separates confirmed absence from transport unavailability. */
class QdrantIndexInitializerTest {
    private static final int EMBEDDING_DIMENSIONS = 2_560;
    private static final int MISMATCHED_COLLECTION_DIMENSIONS = 768;
    private static final String QDRANT_REST_BASE_URL = "http://qdrant.test:6333";
    private static final String QDRANT_DOCKER_REST_BASE_URL = "http://qdrant.test:8087";
    private static final String QDRANT_SCHEMA_TYPE_KEYWORD = "keyword";

    private final Logger initializerLogger = (Logger) LoggerFactory.getLogger(QdrantIndexInitializer.class);
    private ExpectedLogEvents initializerLogEvents;

    @BeforeEach
    void captureInitializerEvents() {
        initializerLogEvents = ExpectedLogEvents.capture(initializerLogger);
    }

    @AfterEach
    void stopCapturingInitializerEvents() {
        initializerLogEvents.close();
    }

    @Test
    void transportFailureDefersInitializationWithoutCreatingCollection() {
        InitializerHarness initializerHarness = newInitializer(true);
        String firstCollectionUrl =
                collectionUrl(initializerHarness.collectionName().get(0));
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(firstCollectionUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(ignoredRequest -> {
                    throw new ResourceAccessException("Qdrant DNS unavailable for test");
                });

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        assertEquals(
                "pending",
                initializerHarness
                        .initializer()
                        .initializationHealth()
                        .getDetails()
                        .get("initialization"));
        assertEquals(1, eventCount(Level.WARN, "Collection initialization deferred"));
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void profileMismatchedCollectionFailsBeforeQdrantMutation() {
        InitializerHarness initializerHarness = newInitializer(true);
        QdrantCollectionNames mismatchedCollectionNames =
                initializerHarness.appProperties().getQdrant().getCollections();
        mismatchedCollectionNames.setDocs("java-chat-dev-qwen3-embedding-4b-2560-docs");
        initializerHarness.appProperties().getQdrant().setCollections(mismatchedCollectionNames);

        assertThrows(IllegalStateException.class, initializerHarness.initializer()::validateGenerationConfiguration);
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void embeddingModelMismatchFailsBeforeQdrantMutation() {
        InitializerHarness initializerHarness = newInitializer(true);
        when(initializerHarness.embeddingClient().modelName()).thenReturn("qwen/qwen3-embedding-8b");

        assertThrows(IllegalStateException.class, initializerHarness.initializer()::validateGenerationConfiguration);
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void embeddingDimensionMismatchFailsBeforeQdrantMutation() {
        InitializerHarness initializerHarness = newInitializer(true);
        when(initializerHarness.embeddingClient().dimensions()).thenReturn(MISMATCHED_COLLECTION_DIMENSIONS);

        assertThrows(IllegalStateException.class, initializerHarness.initializer()::validateGenerationConfiguration);
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void unavailableCreationDefersAtConfiguredRestEndpoint() {
        InitializerHarness initializerHarness = newInitializer(true, 6334);
        String firstCollectionUrl =
                collectionUrl(initializerHarness.collectionName().get(0));
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(firstCollectionUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(firstCollectionUrl))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void tooManyRequestsDefersInitializationWithoutCreatingCollection() {
        InitializerHarness initializerHarness = newInitializer(true);
        initializerHarness
                .qdrantServer()
                .expect(
                        once(),
                        requestTo(collectionUrl(
                                initializerHarness.collectionName().get(0))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        assertEquals(
                "pending",
                initializerHarness
                        .initializer()
                        .initializationHealth()
                        .getDetails()
                        .get("initialization"));
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void dockerGrpcPortCreatesAndValidatesMissingCollectionsAtMappedRestEndpoint() {
        InitializerHarness initializerHarness = newInitializer(true, 8086);
        List<String> collectionNames = initializerHarness.collectionName();

        for (String collectionName : collectionNames) {
            String collectionUrl = collectionUrl(QDRANT_DOCKER_REST_BASE_URL, collectionName);
            initializerHarness
                    .qdrantServer()
                    .expect(once(), requestTo(collectionUrl))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));
            initializerHarness
                    .qdrantServer()
                    .expect(once(), requestTo(collectionUrl))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(jsonPath("$.vectors.dense.size").value(EMBEDDING_DIMENSIONS))
                    .andExpect(jsonPath("$.vectors.dense.distance").value("Cosine"))
                    .andExpect(jsonPath("$.sparse_vectors.bm25.modifier").value("idf"))
                    .andExpect(jsonPath("$.on_disk_payload").value(true))
                    .andRespond(withSuccess());
        }
        for (String collectionName : collectionNames) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_DOCKER_REST_BASE_URL,
                    collectionName,
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }
        for (String collectionName : collectionNames) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_DOCKER_REST_BASE_URL,
                    collectionName,
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.UP,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void pendingInitializationRecoversAfterQdrantReturns() {
        InitializerHarness initializerHarness = newInitializer(false);
        List<String> collectionName = initializerHarness.collectionName();
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(collectionName.get(0))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(ignoredRequest -> {
                    throw new ResourceAccessException("Qdrant temporarily unavailable for test");
                });
        for (String configuredCollection : collectionName) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_REST_BASE_URL,
                    configuredCollection,
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }
        for (String configuredCollection : collectionName) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_REST_BASE_URL,
                    configuredCollection,
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }

        initializerHarness.initializer().ensureCollectionsAndIndexes();
        initializerHarness.initializer().retryPendingInitialization();

        assertEquals(
                Status.UP,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void reachableSchemaMismatchRemainsTerminal() {
        InitializerHarness initializerHarness = newInitializer(false);
        initializerHarness
                .qdrantServer()
                .expect(
                        once(),
                        requestTo(collectionUrl(
                                initializerHarness.collectionName().get(0))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        collectionMetadataJson(MISMATCHED_COLLECTION_DIMENSIONS), MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, initializerHarness.initializer()::ensureCollectionsAndIndexes);

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        assertEquals(
                "failed",
                initializerHarness
                        .initializer()
                        .initializationHealth()
                        .getDetails()
                        .get("initialization"));
        initializerHarness.initializer().retryPendingInitialization();
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void createsExactJavaApiLookupPayloadIndexesWithFilterCompatibleTypes() {
        InitializerHarness initializerHarness = newInitializer(false, true, true);
        AtomicInteger collectionReadCount = new AtomicInteger();
        int readsBeforePostCreationValidation =
                initializerHarness.collectionName().size() * 2;

        expectPayloadIndex(initializerHarness, QdrantPayloadFieldSchema.PACKAGE_FIELD, QDRANT_SCHEMA_TYPE_KEYWORD);
        expectPayloadIndex(initializerHarness, QdrantPayloadFieldSchema.ANCHOR_FIELD, QDRANT_SCHEMA_TYPE_KEYWORD);
        expectPayloadIndex(
                initializerHarness, QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, QDRANT_SCHEMA_TYPE_KEYWORD);
        initializerHarness.qdrantServer().expect(manyTimes(), anything()).andRespond(request -> {
            if (request.getMethod().equals(HttpMethod.GET)) {
                String collectionMetadata = collectionReadCount.incrementAndGet() <= readsBeforePostCreationValidation
                        ? collectionMetadataJsonWithoutPayloadIndexes(EMBEDDING_DIMENSIONS)
                        : collectionMetadataJson(EMBEDDING_DIMENSIONS);
                return withSuccess(collectionMetadata, MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            return withSuccess().createResponse(request);
        });

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.UP,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void disabledPayloadIndexCreationStillRejectsMissingRequiredIndexes() {
        InitializerHarness initializerHarness = newInitializer(false);
        for (String collectionName : initializerHarness.collectionName()) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_REST_BASE_URL,
                    collectionName,
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }
        expectCollectionGet(
                initializerHarness,
                QDRANT_REST_BASE_URL,
                initializerHarness.collectionName().getFirst(),
                collectionMetadataJsonWithoutPayloadIndexes(EMBEDDING_DIMENSIONS));

        assertThrows(IllegalStateException.class, initializerHarness.initializer()::ensureCollectionsAndIndexes);

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    private InitializerHarness newInitializer(boolean ensureCollections) {
        return newInitializer(ensureCollections, 6333);
    }

    private InitializerHarness newInitializer(boolean ensureCollections, int configuredPort) {
        return newInitializer(ensureCollections, configuredPort, false, false);
    }

    private InitializerHarness newInitializer(
            boolean ensureCollections, boolean ensurePayloadIndexes, boolean ignoreExpectationOrder) {
        return newInitializer(ensureCollections, 6333, ensurePayloadIndexes, ignoreExpectationOrder);
    }

    private InitializerHarness newInitializer(
            boolean ensureCollections,
            int configuredPort,
            boolean ensurePayloadIndexes,
            boolean ignoreExpectationOrder) {
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setEnsureCollections(ensureCollections);
        appProperties.getQdrant().setEnsurePayloadIndexes(ensurePayloadIndexes);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.modelName()).thenReturn("qwen/qwen3-embedding-4b");
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);
        AtomicReference<RestTemplate> initializedRestTemplate = new AtomicReference<>();
        RestTemplateBuilder restTemplateBuilder =
                new RestTemplateBuilder().additionalCustomizers(initializedRestTemplate::set);
        QdrantIndexInitializer initializer = new QdrantIndexInitializer(
                new QdrantRestConnection(new QdrantConnectionProperties("qdrant.test", configuredPort, false, "")),
                appProperties,
                restTemplateBuilder,
                embeddingClient,
                new ObjectMapper(),
                "prod");
        MockRestServiceServer.MockRestServiceServerBuilder qdrantServerBuilder =
                MockRestServiceServer.bindTo(initializedRestTemplate.get());
        MockRestServiceServer qdrantServer = ignoreExpectationOrder
                ? qdrantServerBuilder.ignoreExpectOrder(true).build()
                : qdrantServerBuilder.build();
        return new InitializerHarness(
                initializer,
                qdrantServer,
                appProperties,
                embeddingClient,
                appProperties.getQdrant().getCollections().all());
    }

    private void expectPayloadIndex(InitializerHarness initializerHarness, String fieldName, String fieldSchemaType) {
        initializerHarness
                .qdrantServer()
                .expect(times(initializerHarness.collectionName().size()), requestTo(Matchers.containsString("/index")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.field_name").value(fieldName))
                .andExpect(jsonPath("$.field_schema.type").value(fieldSchemaType))
                .andRespond(withSuccess());
    }

    private String collectionUrl(String collectionName) {
        return collectionUrl(QDRANT_REST_BASE_URL, collectionName);
    }

    private String collectionUrl(String baseUrl, String collectionName) {
        return baseUrl + "/collections/" + collectionName;
    }

    private void expectCollectionGet(
            InitializerHarness initializerHarness, String baseUrl, String collectionName, String responseBody) {
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(baseUrl, collectionName)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private String collectionMetadataJson(int denseVectorDimensions) {
        return """
                {
                  "result": {
                    "config": {
                      "params": {
                        "vectors": {"dense": {"size": ${DENSE_VECTOR_DIMENSIONS}, "distance": "Cosine"}},
                        "sparse_vectors": {"bm25": {"modifier": "Idf"}},
                        "on_disk_payload": true
                      }
                    },
                    "payload_schema": {
                      "url": {"data_type": "keyword"},
                      "hash": {"data_type": "keyword"},
                      "chunkIndex": {"data_type": "integer"},
                      "package": {"data_type": "keyword"},
                      "anchor": {"data_type": "keyword"},
                      "javaApiTypePage": {"data_type": "keyword"},
                      "docSet": {"data_type": "keyword"},
                      "docPath": {"data_type": "keyword"},
                      "sourceName": {"data_type": "keyword"},
                      "sourceKind": {"data_type": "keyword"},
                      "docVersion": {"data_type": "keyword"},
                      "docType": {"data_type": "keyword"},
                      "repoUrl": {"data_type": "keyword"},
                      "repoOwner": {"data_type": "keyword"},
                      "repoName": {"data_type": "keyword"},
                      "repoKey": {"data_type": "keyword"},
                      "repoBranch": {"data_type": "keyword"},
                      "commitHash": {"data_type": "keyword"},
                      "license": {"data_type": "keyword"}
                    }
                  }
                }
                """.replace("${DENSE_VECTOR_DIMENSIONS}", Integer.toString(denseVectorDimensions));
    }

    private String collectionMetadataJsonWithoutPayloadIndexes(int denseVectorDimensions) {
        return """
                {
                  "result": {
                    "config": {
                      "params": {
                        "vectors": {"dense": {"size": ${DENSE_VECTOR_DIMENSIONS}, "distance": "Cosine"}},
                        "sparse_vectors": {"bm25": {"modifier": "Idf"}},
                        "on_disk_payload": true
                      }
                    },
                    "payload_schema": {}
                  }
                }
                """.replace("${DENSE_VECTOR_DIMENSIONS}", Integer.toString(denseVectorDimensions));
    }

    private long eventCount(Level level, String messageFragment) {
        return initializerLogEvents.events().stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }

    private record InitializerHarness(
            QdrantIndexInitializer initializer,
            MockRestServiceServer qdrantServer,
            AppProperties appProperties,
            EmbeddingClient embeddingClient,
            List<String> collectionName) {}
}
