package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.williamcallahan.javachat.service.EmbeddingClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Ensures Qdrant collections and indexes exist and match the configured embedding model.
 *
 * <p>This initializer is intentionally strict:
 * - If a collection is reachable and mismatched (dimensions / hybrid config), startup fails.
 * - If payload index creation is enabled and cannot be applied, startup fails.
 *
 * <p>This behavior prevents silent ingestion/retrieval failures when switching embedding providers
 * or adopting hybrid (dense + sparse) collection schemas.</p>
 */
@org.springframework.context.annotation.Profile("!test")
@Component
public class QdrantIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(QdrantIndexInitializer.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private static final int QDRANT_REST_PORT = 6333;
    private static final int QDRANT_GRPC_PORT = 6334;
    private static final int DOCKER_GRPC_PORT = 8086;
    private static final int DOCKER_REST_PORT = 8087;

    private static final String SCHEMA_TYPE_KEYWORD = "keyword";
    private static final String SCHEMA_TYPE_INTEGER = "integer";

    private static final String VECTOR_DISTANCE_COSINE = "Cosine";
    private static final String SPARSE_MODIFIER_IDF = "idf";
    private static final List<PayloadIndexSpec> REQUIRED_PAYLOAD_INDEXES = List.of(
            new PayloadIndexSpec("url", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("hash", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("chunkIndex", SCHEMA_TYPE_INTEGER),
            new PayloadIndexSpec("docSet", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("docPath", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("sourceName", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("sourceKind", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("docVersion", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("docType", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("repoUrl", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("repoOwner", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("repoName", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("repoKey", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("repoBranch", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("commitHash", SCHEMA_TYPE_KEYWORD),
            new PayloadIndexSpec("license", SCHEMA_TYPE_KEYWORD));

    @Value("${spring.ai.vectorstore.qdrant.host}")
    private String host;

    // Note: 6334 is gRPC; REST is 6333. We keep this for gRPC config but compute REST URL separately.
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates the initializer with strict startup validation of Qdrant collections and indexes.
     *
     * @param appProperties application configuration for collection and vector names
     * @param restTemplateBuilder Spring-managed builder for REST calls to Qdrant
     * @param embeddingClient embedding client used to resolve expected vector dimensions
     * @param objectMapper JSON mapper used to build Qdrant request payloads
     */
    public QdrantIndexInitializer(
            AppProperties appProperties,
            RestTemplateBuilder restTemplateBuilder,
            EmbeddingClient embeddingClient,
            ObjectMapper objectMapper) {
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

    /**
     * Ensures configured collections exist and required payload indexes are present at startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureCollectionsAndIndexes() {
        AppProperties.Qdrant qdrant = appProperties.getQdrant();
        List<String> collections = qdrant.getCollections().all();
        String denseVectorName = qdrant.getDenseVectorName();
        String sparseVectorName = qdrant.getSparseVectorName();

        if (collections.isEmpty()) {
            throw new IllegalStateException("app.qdrant.collections must not be empty");
        }

        if (qdrant.isEnsureCollections()) {
            ensureHybridCollectionsExist(collections, denseVectorName, sparseVectorName);
        }

        validateCollections(collections, denseVectorName, sparseVectorName);

        if (!qdrant.isEnsurePayloadIndexes()) {
            log.info("[QDRANT] Skipping payload index ensure (app.qdrant.ensure-payload-indexes=false)");
            return;
        }

        ensurePayloadIndexes(collections);
    }

    private void ensureHybridCollectionsExist(
            List<String> collections, String denseVectorName, String sparseVectorName) {
        int dimensions = embeddingClient.dimensions();
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            if (collection == null || collection.isBlank()) {
                throw new IllegalStateException("Qdrant collection name must not be blank");
            }
            if (collectionExists(collection, headers)) {
                continue;
            }
            createHybridCollection(collection, denseVectorName, sparseVectorName, dimensions, headers);
        }
    }

    private boolean collectionExists(String collection, HttpHeaders headers) {
        String path = "/collections/" + collection;
        boolean observedNotFound = false;
        for (String base : restBaseUrls()) {
            String url = base + path;
            try {
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                log.info("[QDRANT] Collection present (collection={})", collection);
                return true;
            } catch (HttpClientErrorException.NotFound notFoundException) {
                observedNotFound = true;
                log.debug(
                        "[QDRANT] Collection lookup returned 404 (collection={}, base={})",
                        collection,
                        base);
            } catch (RuntimeException exception) {
                log.debug(
                        "[QDRANT] Collection existence check failed (exceptionType={})",
                        exception.getClass().getSimpleName());
            }
        }
        if (observedNotFound) {
            log.debug("[QDRANT] Collection missing after probing candidate base URLs (collection={})", collection);
        }
        return false;
    }

    private void createHybridCollection(
            String collection, String denseVectorName, String sparseVectorName, int dimensions, HttpHeaders headers) {
        Objects.requireNonNull(collection, "collection");
        if (collection.isBlank()) {
            throw new IllegalArgumentException("collection must not be blank");
        }
        if (denseVectorName == null || denseVectorName.isBlank()) {
            throw new IllegalArgumentException("denseVectorName must not be blank");
        }
        if (sparseVectorName == null || sparseVectorName.isBlank()) {
            throw new IllegalArgumentException("sparseVectorName must not be blank");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }

        ObjectNodeBuilder body = new ObjectNodeBuilder(objectMapper);
        body.putObject("vectors")
                .putObject(denseVectorName)
                .put("size", dimensions)
                .put("distance", VECTOR_DISTANCE_COSINE);
        body.putObject("sparse_vectors").putObject(sparseVectorName).put("modifier", SPARSE_MODIFIER_IDF);
        body.put("on_disk_payload", true);

        String path = "/collections/" + collection;
        RestClientResponseException lastHttp = null;
        RuntimeException lastRuntime = null;
        for (String base : restBaseUrls()) {
            String url = base + path;
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.PUT, new HttpEntity<>(body.root(), headers), String.class);
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Qdrant create collection returned HTTP " + status);
                }
                log.info("[QDRANT] Created hybrid collection (collection={})", collection);
                return;
            } catch (RestClientResponseException httpError) {
                lastHttp = httpError;
            } catch (RuntimeException runtimeError) {
                lastRuntime = runtimeError;
            }
        }

        if (lastHttp != null) {
            throw new IllegalStateException(
                    "Failed to create Qdrant collection '" + collection + "' (HTTP "
                            + lastHttp.getStatusCode().value() + ")",
                    lastHttp);
        }
        throw new IllegalStateException("Failed to create Qdrant collection '" + collection + "'", lastRuntime);
    }

    private void validateCollections(List<String> collections, String denseVectorName, String sparseVectorName) {
        int expectedDimensions = embeddingClient.dimensions();
        if (expectedDimensions <= 0) {
            throw new IllegalStateException("Embedding model dimensions must be positive");
        }
        if (denseVectorName == null || denseVectorName.isBlank()) {
            throw new IllegalStateException("app.qdrant.dense-vector-name must not be blank");
        }
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            JsonNode info = fetchCollectionInfo(collection, headers);
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

    private JsonNode fetchCollectionInfo(String collection, HttpHeaders headers) {
        Objects.requireNonNull(collection, "collection");
        String path = "/collections/" + collection;

        RestClientResponseException lastHttp = null;
        RuntimeException lastRuntime = null;
        for (String base : restBaseUrls()) {
            String url = base + path;
            try {
                ResponseEntity<JsonNode> response =
                        restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("Qdrant collection info returned HTTP " + response.getStatusCode());
                }
                JsonNode body = response.getBody();
                if (body == null || body.isNull()) {
                    throw new IllegalStateException("Qdrant collection info was null for collection=" + collection);
                }
                return body;
            } catch (RestClientResponseException httpError) {
                lastHttp = httpError;
            } catch (RuntimeException runtimeError) {
                lastRuntime = runtimeError;
            }
        }
        if (lastHttp != null) {
            throw new IllegalStateException(
                    "Failed to fetch Qdrant collection info for '" + collection + "' (HTTP "
                            + lastHttp.getStatusCode().value() + ")",
                    lastHttp);
        }
        throw new IllegalStateException("Failed to fetch Qdrant collection info for '" + collection + "'", lastRuntime);
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
        if (sparseVectors.isMissingNode() || sparseVectors.isNull() || !sparseVectors.isObject()) {
            return false;
        }
        return sparseVectors.has(sparseVectorName);
    }

    private void ensurePayloadIndexes(List<String> collections) {
        HttpHeaders headers = jsonHeaders();
        for (String collection : collections) {
            Map<String, String> existingPayloadIndexTypes = readExistingPayloadIndexTypes(collection, headers);
            int createdIndexCount = 0;
            int alreadyPresentIndexCount = 0;
            for (PayloadIndexSpec payloadIndexSpec : REQUIRED_PAYLOAD_INDEXES) {
                String existingType = existingPayloadIndexTypes.get(payloadIndexSpec.fieldName());
                if (existingType == null || existingType.isBlank()) {
                    ensurePayloadIndex(
                            collection, payloadIndexSpec.fieldName(), payloadIndexSpec.schemaType(), headers);
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

    private Map<String, String> readExistingPayloadIndexTypes(String collection, HttpHeaders headers) {
        JsonNode collectionInfo = fetchCollectionInfo(collection, headers);
        JsonNode payloadSchemaNode = collectionInfo.path("result").path("payload_schema");
        if (payloadSchemaNode.isMissingNode() || payloadSchemaNode.isNull() || !payloadSchemaNode.isObject()) {
            return Map.of();
        }

        LinkedHashMap<String, String> payloadIndexTypes = new LinkedHashMap<>();
        // Using properties() method (Jackson 2.13+) instead of deprecated fields()
        for (Iterator<Map.Entry<String, JsonNode>> it =
                        payloadSchemaNode.properties().iterator();
                it.hasNext(); ) {
            Map.Entry<String, JsonNode> fieldEntry = it.next();
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
        if (fallbackTypeNode.isTextual()) {
            return fallbackTypeNode.asText().trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private void ensurePayloadIndex(String collection, String field, String schemaType, HttpHeaders headers) {
        PayloadIndexRequest body = new PayloadIndexRequest(field, Map.of("type", schemaType));
        String path = "/collections/" + collection + "/index";

        RestClientResponseException lastHttp = null;
        RuntimeException lastRuntime = null;
        for (String base : restBaseUrls()) {
            String url = base + path;
            try {
                ResponseEntity<String> response =
                        restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("Qdrant payload index ensure returned HTTP "
                            + response.getStatusCode().value());
                }
                log.info("[QDRANT] Ensured payload index (collection={}, field={})", collection, field);
                return;
            } catch (RestClientResponseException httpError) {
                lastHttp = httpError;
            } catch (RuntimeException runtimeError) {
                lastRuntime = runtimeError;
            }
        }

        if (lastHttp != null) {
            throw new IllegalStateException(
                    "Failed to ensure payload index (collection="
                            + collection
                            + ", field="
                            + field
                            + ", HTTP "
                            + lastHttp.getStatusCode().value()
                            + ")",
                    lastHttp);
        }
        throw new IllegalStateException(
                "Failed to ensure payload index (collection=" + collection + ", field=" + field + ")", lastRuntime);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        return headers;
    }

    private List<String> restBaseUrls() {
        List<String> bases = new ArrayList<>();
        if (useTls) {
            bases.add("https://" + host); // Cloud REST via 443
        } else {
            bases.add("http://" + host + ":" + QDRANT_REST_PORT);
            if (port == QDRANT_GRPC_PORT) {
                bases.add("http://" + host + ":" + QDRANT_GRPC_PORT);
            } else if (port == DOCKER_GRPC_PORT) {
                bases.add("http://" + host + ":" + DOCKER_REST_PORT);
            }
            bases.add("http://" + host + ":" + port);
        }
        return bases;
    }

    private record PayloadIndexRequest(
            @JsonProperty("field_name") String fieldName,
            @JsonProperty("field_schema") Map<String, String> fieldSchema) {}

    private record PayloadIndexSpec(String fieldName, String schemaType) {}

    /**
     * Small helper to build a JSON object without exposing maps across the initializer.
     */
    private static final class ObjectNodeBuilder {
        private final ObjectNode root;

        private ObjectNodeBuilder(ObjectMapper mapper) {
            this.root = mapper.createObjectNode();
        }

        ObjectNode root() {
            return root;
        }

        ObjectNodeBuilder put(String key, boolean value) {
            root.put(key, value);
            return this;
        }

        ObjectNode putObject(String key) {
            return root.putObject(key);
        }
    }
}
