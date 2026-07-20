package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Ensures Qdrant collections and indexes exist and match the configured embedding model.
 *
 * <p>Transient outages defer creation and trigger retries; reachable configuration and schema defects fail.</p>
 */
@org.springframework.context.annotation.Profile("!test")
@Component
public final class QdrantIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(QdrantIndexInitializer.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final long INITIALIZATION_RETRY_MILLIS = 30_000L;
    private static final String SCHEMA_TYPE_KEYWORD = "keyword";
    private static final String SCHEMA_TYPE_INTEGER = "integer";
    private static final String VECTOR_DISTANCE_COSINE = "Cosine";
    private static final String SPARSE_MODIFIER_IDF = "idf";
    private static final String REQUIRED_EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";
    private static final int REQUIRED_EMBEDDING_DIMENSIONS = 2_560;
    private static final String EMPTY_TEXT = "";
    private static final List<PayloadIndexSpec> REQUIRED_PAYLOAD_INDEXES = List.of(
            new PayloadIndexSpec(QdrantPayloadFieldSchema.URL_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.HASH_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.CHUNK_INDEX_FIELD, SCHEMA_TYPE_INTEGER),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.PACKAGE_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.ANCHOR_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.DOC_SET_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.DOC_PATH_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.SOURCE_NAME_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.REPO_URL_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.REPO_OWNER_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.REPO_NAME_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.REPO_KEY_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.REPO_BRANCH_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.COMMIT_HASH_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.LICENSE_FIELD, SCHEMA_TYPE_KEYWORD));

    private final QdrantRestConnection qdrantRestConnection;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final String deploymentProfile;
    private volatile QdrantInitializationState initializationState = QdrantInitializationState.PENDING;

    /** Creates the initializer with strict validation of reachable Qdrant schemas. */
    public QdrantIndexInitializer(
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties,
            RestTemplateBuilder restTemplateBuilder,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            @Value("${SPRING_PROFILE:prod}") String deploymentProfile) {
        this.qdrantRestConnection = Objects.requireNonNull(qdrantRestConnection, "qdrantRestConnection");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.deploymentProfile = requireDeploymentProfile(deploymentProfile);
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().add("Connection", "close");
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Rejects cross-environment collection routing before command-line runners or web traffic can mutate Qdrant. */
    @PostConstruct
    void validateGenerationConfiguration() {
        validateGenerationCollections(appProperties.getQdrant().getCollections().all());
        validateEmbeddingGeneration();
    }

    /** Ensures configured collections exist and required payload indexes are present at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureCollectionsAndIndexes() {
        attemptInitialization(true);
    }

    /** Retries initialization after transient Qdrant transport failures without restarting the JVM. */
    @Scheduled(initialDelay = INITIALIZATION_RETRY_MILLIS, fixedDelay = INITIALIZATION_RETRY_MILLIS)
    void retryPendingInitialization() {
        attemptInitialization(false);
    }

    Health initializationHealth() {
        return switch (initializationState) {
            case READY -> Health.up().withDetail("initialization", "ready").build();
            case PENDING ->
                Health.down().withDetail("initialization", "pending").build();
            case FAILED -> Health.down().withDetail("initialization", "failed").build();
        };
    }

    private synchronized void attemptInitialization(boolean startupAttempt) {
        if (initializationState != QdrantInitializationState.PENDING) {
            return;
        }
        try {
            initializeCollectionsAndIndexes();
            initializationState = QdrantInitializationState.READY;
            log.info("[QDRANT] Collection initialization ready");
        } catch (QdrantUnavailableException unavailableException) {
            if (startupAttempt) {
                log.warn(
                        "[QDRANT] Collection initialization deferred because Qdrant is unavailable: {}",
                        unavailableException.getMessage());
            } else {
                log.debug(
                        "[QDRANT] Collection initialization still pending because Qdrant is unavailable: {}",
                        unavailableException.getMessage());
            }
        } catch (RuntimeException configurationOrSchemaException) {
            initializationState = QdrantInitializationState.FAILED;
            if (startupAttempt) {
                throw configurationOrSchemaException;
            }
            log.error(
                    "[QDRANT] Collection initialization failed after connectivity recovered; manual correction is required",
                    configurationOrSchemaException);
        }
    }

    private void initializeCollectionsAndIndexes() {
        validateEmbeddingGeneration();
        QdrantProperties qdrant = appProperties.getQdrant();
        List<String> collections = qdrant.getCollections().all();
        validateGenerationCollections(collections);
        String denseVectorName = qdrant.getDenseVectorName();
        String sparseVectorName = qdrant.getSparseVectorName();
        String restBaseUrl = qdrantRestConnection.restBaseUrl();

        if (collections.isEmpty()) {
            throw new IllegalStateException("app.qdrant.collections must not be empty");
        }

        if (qdrant.isEnsureCollections()) {
            ensureHybridCollectionsExist(collections, denseVectorName, sparseVectorName, restBaseUrl);
        }

        validateCollections(collections, denseVectorName, sparseVectorName, restBaseUrl);

        if (qdrant.isEnsurePayloadIndexes()) {
            ensurePayloadIndexes(collections, restBaseUrl);
        } else {
            log.info("[QDRANT] Payload index creation disabled; validating required indexes without mutation");
        }
        validatePayloadIndexes(collections, restBaseUrl);
    }

    private void validateEmbeddingGeneration() {
        if (!REQUIRED_EMBEDDING_MODEL.equals(embeddingClient.modelName())
                || embeddingClient.dimensions() != REQUIRED_EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "Core Qdrant generation requires qwen/qwen3-embedding-4b with exactly 2560 dimensions");
        }
    }

    private void validateGenerationCollections(List<String> collections) {
        List<String> expectedCollections = List.of(
                "java-chat-" + deploymentProfile + "-qwen3-embedding-4b-2560-books",
                "java-chat-" + deploymentProfile + "-qwen3-embedding-4b-2560-docs",
                "java-chat-" + deploymentProfile + "-qwen3-embedding-4b-2560-articles",
                "java-chat-" + deploymentProfile + "-qwen3-embedding-4b-2560-pdfs");
        if (!expectedCollections.equals(collections)) {
            throw new IllegalStateException(
                    "Core Qdrant collections must match SPRING_PROFILE and the 4B/2560 generation");
        }
    }

    private static String requireDeploymentProfile(String deploymentProfile) {
        if (!"local".equals(deploymentProfile)
                && !"dev".equals(deploymentProfile)
                && !"prod".equals(deploymentProfile)) {
            throw new IllegalStateException("SPRING_PROFILE must be exactly local, dev, or prod");
        }
        return deploymentProfile;
    }

    private void ensureHybridCollectionsExist(
            List<String> collections, String denseVectorName, String sparseVectorName, String restBaseUrl) {
        int dimensions = embeddingClient.dimensions();
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            if (collection == null || collection.isBlank()) {
                throw new IllegalStateException("Qdrant collection name must not be blank");
            }
            CollectionRestTarget target = new CollectionRestTarget(restBaseUrl, collection, headers);
            if (!collectionExists(target)) {
                createHybridCollection(
                        target, new HybridCollectionSchema(denseVectorName, sparseVectorName, dimensions));
            }
        }
    }

    private boolean collectionExists(CollectionRestTarget target) {
        String collection = target.collection();
        try {
            restTemplate.exchange(
                    target.baseUrl() + "/collections/" + collection,
                    HttpMethod.GET,
                    new HttpEntity<>(target.headers()),
                    String.class);
            log.info("[QDRANT] Collection present (collection={}, base={})", collection, target.baseUrl());
            return true;
        } catch (HttpClientErrorException.NotFound notFoundException) {
            log.debug("[QDRANT] Collection missing (collection={}, base={})", collection, target.baseUrl());
            return false;
        } catch (RestClientResponseException responseException) {
            throw classifyHttpFailure("look up Qdrant collection '" + collection + "'", responseException);
        } catch (ResourceAccessException transportException) {
            throw unavailable("look up Qdrant collection '" + collection + "'", transportException);
        }
    }

    private void createHybridCollection(CollectionRestTarget target, HybridCollectionSchema schema) {
        if (schema.denseVectorName() == null
                || schema.denseVectorName().isBlank()
                || schema.sparseVectorName() == null
                || schema.sparseVectorName().isBlank()
                || schema.dimensions() <= 0) {
            throw new IllegalArgumentException("Qdrant vector names and embedding dimensions must be configured");
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody
                .putObject("vectors")
                .putObject(schema.denseVectorName())
                .put("size", schema.dimensions())
                .put("distance", VECTOR_DISTANCE_COSINE);
        requestBody
                .putObject("sparse_vectors")
                .putObject(schema.sparseVectorName())
                .put("modifier", SPARSE_MODIFIER_IDF);
        requestBody.put("on_disk_payload", true);

        String collectionUrl = target.baseUrl() + "/collections/" + target.collection();
        try {
            restTemplate.exchange(
                    collectionUrl, HttpMethod.PUT, new HttpEntity<>(requestBody, target.headers()), String.class);
            log.info("[QDRANT] Created hybrid collection (collection={})", target.collection());
        } catch (RestClientResponseException responseException) {
            throw classifyHttpFailure("create Qdrant collection '" + target.collection() + "'", responseException);
        } catch (ResourceAccessException transportException) {
            throw unavailable("create Qdrant collection '" + target.collection() + "'", transportException);
        }
    }

    private void validateCollections(
            List<String> collections, String denseVectorName, String sparseVectorName, String restBaseUrl) {
        int expectedDimensions = embeddingClient.dimensions();
        if (expectedDimensions <= 0) {
            throw new IllegalStateException("Embedding model dimensions must be positive");
        }
        if (denseVectorName == null || denseVectorName.isBlank()) {
            throw new IllegalStateException("app.qdrant.dense-vector-name must not be blank");
        }
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            JsonNode info = fetchCollectionInfo(restBaseUrl, collection, headers);
            int actualDimensions = extractDenseVectorDimensions(info, denseVectorName);
            if (actualDimensions != expectedDimensions) {
                throw new IllegalStateException("Qdrant collection dimension mismatch for '"
                        + collection
                        + "': expected "
                        + expectedDimensions
                        + " but found "
                        + actualDimensions
                        + ". Recreate the collection to match the embedding provider.");
            }
            if (!hasSparseVectorConfig(info, sparseVectorName)) {
                throw new IllegalStateException("Qdrant collection '"
                        + collection
                        + "' is missing sparse_vectors['"
                        + sparseVectorName
                        + "'] required for hybrid retrieval.");
            }
            validateDenseDistance(info, denseVectorName, collection);
            validateSparseModifier(info, sparseVectorName, collection);
            if (!info.path("result")
                    .path("config")
                    .path("params")
                    .path("on_disk_payload")
                    .asBoolean(false)) {
                throw new IllegalStateException("Qdrant collection '" + collection + "' must store payload on disk.");
            }
        }
    }

    private void validateDenseDistance(JsonNode collectionInfo, String denseVectorName, String collection) {
        String denseDistance = collectionInfo
                .path("result")
                .path("config")
                .path("params")
                .path("vectors")
                .path(denseVectorName)
                .path("distance")
                .asText(EMPTY_TEXT);
        if (!VECTOR_DISTANCE_COSINE.equalsIgnoreCase(denseDistance)) {
            throw new IllegalStateException("Qdrant collection '" + collection + "' dense vector distance must be "
                    + VECTOR_DISTANCE_COSINE + " but found '" + denseDistance + "'.");
        }
    }

    private void validateSparseModifier(JsonNode collectionInfo, String sparseVectorName, String collection) {
        String sparseModifier = collectionInfo
                .path("result")
                .path("config")
                .path("params")
                .path("sparse_vectors")
                .path(sparseVectorName)
                .path("modifier")
                .asText(EMPTY_TEXT);
        if (!SPARSE_MODIFIER_IDF.equalsIgnoreCase(sparseModifier)) {
            throw new IllegalStateException("Qdrant collection '" + collection + "' sparse vector modifier must be "
                    + SPARSE_MODIFIER_IDF + " but found '" + sparseModifier + "'.");
        }
    }

    private JsonNode fetchCollectionInfo(String baseUrl, String collection, HttpHeaders headers) {
        Objects.requireNonNull(collection, "collection");
        String collectionUrl = baseUrl + "/collections/" + collection;
        try {
            ResponseEntity<JsonNode> qdrantResponse =
                    restTemplate.exchange(collectionUrl, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            JsonNode collectionInfo = qdrantResponse.getBody();
            if (collectionInfo == null || collectionInfo.isNull()) {
                throw new IllegalStateException("Qdrant collection info was null for collection=" + collection);
            }
            return collectionInfo;
        } catch (RestClientResponseException responseException) {
            throw classifyHttpFailure("fetch Qdrant collection info for '" + collection + "'", responseException);
        } catch (ResourceAccessException transportException) {
            throw unavailable("fetch Qdrant collection info for '" + collection + "'", transportException);
        }
    }

    private int extractDenseVectorDimensions(JsonNode collectionInfo, String denseVectorName) {
        JsonNode vectors =
                collectionInfo.path("result").path("config").path("params").path("vectors");
        if (vectors.isMissingNode() || vectors.isNull()) {
            throw new IllegalStateException("Qdrant collection vectors config missing");
        }
        if (!vectors.isObject()) {
            throw new IllegalStateException(
                    "Qdrant collection vectors config must use named-vector mode for hybrid collections.");
        }

        if (denseVectorName == null || denseVectorName.isBlank()) {
            throw new IllegalStateException("app.qdrant.dense-vector-name must not be blank");
        }

        JsonNode namedVector = vectors.path(denseVectorName);
        if (namedVector.isMissingNode() || namedVector.isNull()) {
            throw new IllegalStateException(
                    "Qdrant collection missing vectors['" + denseVectorName + "'] required for hybrid upserts.");
        }

        JsonNode sizeNode = namedVector.path("size");
        if (!sizeNode.isNumber()) {
            throw new IllegalStateException(
                    "Qdrant collection vectors['" + denseVectorName + "'].size missing or invalid");
        }
        return sizeNode.intValue();
    }

    private boolean hasSparseVectorConfig(JsonNode collectionInfo, String sparseVectorName) {
        if (sparseVectorName == null || sparseVectorName.isBlank()) {
            return false;
        }
        JsonNode sparseVectors =
                collectionInfo.path("result").path("config").path("params").path("sparse_vectors");
        return sparseVectors.isObject() && sparseVectors.has(sparseVectorName);
    }

    private void ensurePayloadIndexes(List<String> collections, String restBaseUrl) {
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            CollectionRestTarget target = new CollectionRestTarget(restBaseUrl, collection, headers);
            Map<String, String> existingPayloadIndexTypes =
                    readExistingPayloadIndexTypes(restBaseUrl, collection, headers);
            int createdIndexCount = 0;
            int alreadyPresentIndexCount = 0;
            for (PayloadIndexSpec payloadIndexSpec : REQUIRED_PAYLOAD_INDEXES) {
                String existingType = existingPayloadIndexTypes.get(payloadIndexSpec.fieldName());
                if (existingType == null || existingType.isBlank()) {
                    ensurePayloadIndex(target, payloadIndexSpec);
                    createdIndexCount++;
                    continue;
                }
                validatePayloadIndexType(collection, payloadIndexSpec, existingType);
                alreadyPresentIndexCount++;
            }
            log.info(
                    "[QDRANT] Payload index ensure complete (collection={}, created={}, alreadyPresent={})",
                    collection,
                    createdIndexCount,
                    alreadyPresentIndexCount);
        }
    }

    private void validatePayloadIndexes(List<String> collections, String restBaseUrl) {
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            Map<String, String> existingPayloadIndexTypes =
                    readExistingPayloadIndexTypes(restBaseUrl, collection, headers);
            for (PayloadIndexSpec payloadIndexSpec : REQUIRED_PAYLOAD_INDEXES) {
                String existingType = existingPayloadIndexTypes.get(payloadIndexSpec.fieldName());
                if (existingType == null || existingType.isBlank()) {
                    throw new IllegalStateException("Qdrant required payload index missing (collection="
                            + collection
                            + ", field="
                            + payloadIndexSpec.fieldName()
                            + ")");
                }
                validatePayloadIndexType(collection, payloadIndexSpec, existingType);
            }
        }
    }

    private void validatePayloadIndexType(
            String collection, PayloadIndexSpec payloadIndexSpec, String existingPayloadIndexType) {
        if (!existingPayloadIndexType.equals(payloadIndexSpec.schemaType())) {
            throw new IllegalStateException("Qdrant payload index type mismatch (collection="
                    + collection
                    + ", field="
                    + payloadIndexSpec.fieldName()
                    + ", expected="
                    + payloadIndexSpec.schemaType()
                    + ", actual="
                    + existingPayloadIndexType
                    + ")");
        }
    }

    private Map<String, String> readExistingPayloadIndexTypes(String baseUrl, String collection, HttpHeaders headers) {
        JsonNode collectionInfo = fetchCollectionInfo(baseUrl, collection, headers);
        JsonNode payloadSchemaNode = collectionInfo.path("result").path("payload_schema");
        if (payloadSchemaNode.isMissingNode() || payloadSchemaNode.isNull() || !payloadSchemaNode.isObject()) {
            return Map.of();
        }

        LinkedHashMap<String, String> payloadIndexTypes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> fieldEntry : payloadSchemaNode.properties()) {
            String payloadDataType = extractPayloadDataType(fieldEntry.getValue());
            if (!payloadDataType.isBlank()) {
                payloadIndexTypes.put(fieldEntry.getKey(), payloadDataType);
            }
        }
        return Map.copyOf(payloadIndexTypes);
    }

    private String extractPayloadDataType(JsonNode payloadFieldSchema) {
        JsonNode dataTypeNode = payloadFieldSchema.path("data_type");
        if (dataTypeNode.isTextual()) {
            return dataTypeNode.asText().trim().toLowerCase(Locale.ROOT);
        }
        if (dataTypeNode.isArray() && !dataTypeNode.isEmpty()) {
            JsonNode firstDataTypeNode = dataTypeNode.get(0);
            if (firstDataTypeNode != null && firstDataTypeNode.isTextual()) {
                return firstDataTypeNode.asText().trim().toLowerCase(Locale.ROOT);
            }
        }
        JsonNode fallbackTypeNode = payloadFieldSchema.path("type");
        return fallbackTypeNode.isTextual() ? fallbackTypeNode.asText().trim().toLowerCase(Locale.ROOT) : "";
    }

    private void ensurePayloadIndex(CollectionRestTarget target, PayloadIndexSpec indexSpec) {
        String collection = target.collection();
        String fieldName = indexSpec.fieldName();
        PayloadIndexRequest indexRequest = new PayloadIndexRequest(fieldName, Map.of("type", indexSpec.schemaType()));
        String indexUrl = target.baseUrl() + "/collections/" + collection + "/index?wait=true";
        String operation = "ensure Qdrant payload index '%s' for collection '%s'".formatted(fieldName, collection);
        try {
            restTemplate.exchange(
                    indexUrl, HttpMethod.PUT, new HttpEntity<>(indexRequest, target.headers()), String.class);
            log.info("[QDRANT] Ensured payload index (collection={}, field={})", collection, fieldName);
        } catch (RestClientResponseException responseException) {
            throw classifyHttpFailure(operation, responseException);
        } catch (ResourceAccessException transportException) {
            throw unavailable(operation, transportException);
        }
    }

    private RuntimeException classifyHttpFailure(
            String qdrantOperation, RestClientResponseException responseException) {
        HttpStatusCode statusCode = responseException.getStatusCode();
        if (statusCode.is5xxServerError()
                || statusCode.value() == HttpStatus.REQUEST_TIMEOUT.value()
                || statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return unavailable(qdrantOperation, responseException);
        }
        return new IllegalStateException(
                "Failed to " + qdrantOperation + " (HTTP " + statusCode.value() + ")", responseException);
    }

    private QdrantUnavailableException unavailable(String qdrantOperation, RuntimeException transportException) {
        return new QdrantUnavailableException("Failed to " + qdrantOperation, transportException);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String qdrantApiKey = qdrantRestConnection.apiKey();
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set(QdrantRestConnection.API_KEY_HEADER, qdrantApiKey);
        }
        return headers;
    }

    private record PayloadIndexRequest(
            @JsonProperty("field_name") String fieldName,
            @JsonProperty("field_schema") Map<String, String> fieldSchema) {}

    private record PayloadIndexSpec(String fieldName, String schemaType) {}

    private record CollectionRestTarget(String baseUrl, String collection, HttpHeaders headers) {}

    private record HybridCollectionSchema(String denseVectorName, String sparseVectorName, int dimensions) {}

    /** Initialization lifecycle. */
    private enum QdrantInitializationState {
        PENDING,
        READY,
        FAILED
    }

    /** Signals a transient Qdrant failure. */
    private static final class QdrantUnavailableException extends RuntimeException {
        private QdrantUnavailableException(String message, RuntimeException cause) {
            super(message, cause);
        }
    }
}
