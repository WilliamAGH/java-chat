package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class QdrantIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(QdrantIndexInitializer.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final long INITIALIZATION_RETRY_MILLIS = 30_000L;
    private static final String SCHEMA_TYPE_KEYWORD = "keyword";
    private static final String SCHEMA_TYPE_INTEGER = "integer";
    private static final String VECTOR_DISTANCE_COSINE = "Cosine";
    private static final String SPARSE_MODIFIER_IDF = "idf";
    private static final List<PayloadIndexSpec> REQUIRED_PAYLOAD_INDEXES = List.of(
            new PayloadIndexSpec(QdrantPayloadFieldSchema.URL_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.HASH_FIELD, SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec(QdrantPayloadFieldSchema.CHUNK_INDEX_FIELD, SCHEMA_TYPE_INTEGER),
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
    private volatile QdrantInitializationState initializationState = QdrantInitializationState.PENDING;

    /** Creates the initializer with strict validation of reachable Qdrant schemas. */
    public QdrantIndexInitializer(
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties,
            RestTemplateBuilder restTemplateBuilder,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper) {
        this.qdrantRestConnection = Objects.requireNonNull(qdrantRestConnection, "qdrantRestConnection");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .additionalInterceptors((request, body, execution) -> {
                    request.getHeaders().add("Connection", "close");
                    return execution.execute(request, body);
                })
                .build();
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
        QdrantProperties qdrant = appProperties.getQdrant();
        List<String> collections = qdrant.getCollections().all();
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

        if (!qdrant.isEnsurePayloadIndexes()) {
            log.info("[QDRANT] Skipping payload index ensure (app.qdrant.ensure-payload-indexes=false)");
            return;
        }

        ensurePayloadIndexes(collections, restBaseUrl);
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
                if (!existingType.equals(payloadIndexSpec.schemaType())) {
                    throw new IllegalStateException("Qdrant payload index type mismatch (collection="
                            + collection
                            + ", field="
                            + payloadIndexSpec.fieldName()
                            + ", expected="
                            + payloadIndexSpec.schemaType()
                            + ", actual="
                            + existingType
                            + ")");
                }
                alreadyPresentIndexCount++;
            }
            log.info(
                    "[QDRANT] Payload index ensure complete (collection={}, created={}, alreadyPresent={})",
                    collection,
                    createdIndexCount,
                    alreadyPresentIndexCount);
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
        String indexUrl = target.baseUrl() + "/collections/" + collection + "/index";
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
