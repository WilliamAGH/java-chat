package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final int EMBEDDING_DIMENSIONS = 1_536;
    private static final int MISMATCHED_COLLECTION_DIMENSIONS = 768;
    private static final String QDRANT_REST_BASE_URL = "http://qdrant.test:6333";
    private static final String QDRANT_FALLBACK_BASE_URL = "http://qdrant.test:7444";

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
    void defaultRestNotFoundCreatesWithoutProbingConfiguredGrpcPort() {
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
    void mixedMissingAndTransportFailureDoesNotCreateCollection() {
        InitializerHarness initializerHarness = newInitializer(true, 7444);
        String collectionName = initializerHarness.collectionName().get(0);
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(collectionName)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(QDRANT_FALLBACK_BASE_URL, collectionName)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(ignoredRequest -> {
                    throw new ResourceAccessException("fallback transport unavailable for test");
                });

        initializerHarness.initializer().ensureCollectionsAndIndexes();

        assertEquals(
                Status.DOWN,
                initializerHarness.initializer().initializationHealth().getStatus());
        initializerHarness.qdrantServer().verify();
    }

    @Test
    void fallbackCandidateSuccessWinsAndValidationUsesThatBase() {
        InitializerHarness initializerHarness = newInitializer(true, 7444);
        List<String> collectionName = initializerHarness.collectionName();
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(collectionName.get(0))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        initializerHarness
                .qdrantServer()
                .expect(once(), requestTo(collectionUrl(QDRANT_FALLBACK_BASE_URL, collectionName.get(0))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(collectionMetadataJson(EMBEDDING_DIMENSIONS), MediaType.APPLICATION_JSON));
        for (int collectionIndex = 1; collectionIndex < collectionName.size(); collectionIndex++) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_REST_BASE_URL,
                    collectionName.get(collectionIndex),
                    collectionMetadataJson(EMBEDDING_DIMENSIONS));
        }
        expectCollectionGet(
                initializerHarness,
                QDRANT_FALLBACK_BASE_URL,
                collectionName.get(0),
                collectionMetadataJson(EMBEDDING_DIMENSIONS));
        for (int collectionIndex = 1; collectionIndex < collectionName.size(); collectionIndex++) {
            expectCollectionGet(
                    initializerHarness,
                    QDRANT_REST_BASE_URL,
                    collectionName.get(collectionIndex),
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

    private InitializerHarness newInitializer(boolean ensureCollections) {
        return newInitializer(ensureCollections, 6333);
    }

    private InitializerHarness newInitializer(boolean ensureCollections, int configuredPort) {
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setEnsureCollections(ensureCollections);
        appProperties.getQdrant().setEnsurePayloadIndexes(false);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.dimensions()).thenReturn(EMBEDDING_DIMENSIONS);
        AtomicReference<RestTemplate> initializedRestTemplate = new AtomicReference<>();
        RestTemplateBuilder restTemplateBuilder =
                new RestTemplateBuilder().additionalCustomizers(initializedRestTemplate::set);
        QdrantIndexInitializer initializer = new QdrantIndexInitializer(
                new QdrantRestConnection(new QdrantConnectionProperties("qdrant.test", configuredPort, false, "")),
                appProperties,
                restTemplateBuilder,
                embeddingClient,
                new ObjectMapper());
        MockRestServiceServer qdrantServer =
                MockRestServiceServer.bindTo(initializedRestTemplate.get()).build();
        return new InitializerHarness(
                initializer,
                qdrantServer,
                appProperties.getQdrant().getCollections().all());
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
                        "vectors": {"dense": {"size": %d}},
                        "sparse_vectors": {"bm25": {}}
                      }
                    },
                    "payload_schema": {}
                  }
                }
                """.formatted(denseVectorDimensions);
    }

    private long eventCount(Level level, String messageFragment) {
        return initializerLogEvents.events().stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(messageFragment))
                .count();
    }

    private record InitializerHarness(
            QdrantIndexInitializer initializer, MockRestServiceServer qdrantServer, List<String> collectionName) {}
}
