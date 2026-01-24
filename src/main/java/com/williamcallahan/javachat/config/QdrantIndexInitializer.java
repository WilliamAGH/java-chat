package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
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
import org.springframework.web.client.RestTemplate;

/**
 * Initializes Qdrant payload indexes on startup and validates collection configuration.
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

    /** Qdrant schema type for keyword (string) fields. */
    private static final String SCHEMA_TYPE_KEYWORD = "keyword";
    /** Qdrant schema type for integer fields. */
    private static final String SCHEMA_TYPE_INTEGER = "integer";

    @Value("${spring.ai.vectorstore.qdrant.host}")
    private String host;

    // Note: 6334 is gRPC; REST is 6333. We keep this for gRPC config but compute REST URL separately.
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name}")
    private String collection;

    private final boolean ensurePayloadIndexes;
    private final RestTemplate restTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * Build candidate REST base URLs for Qdrant.
     * - Cloud (TLS): https://host (port 443 behind gateway)
     * - Local (no TLS): prefer REST default 6333; if a typical gRPC port is configured (6334 or 8086),
     *   try the corresponding REST port (6333 or 8087) as well.
     */
    private List<String> restBaseUrls() {
        List<String> bases = new ArrayList<>();
        if (useTls) {
            bases.add("https://" + host); // Cloud REST via 443
        } else {
            // Always try the REST default first
            bases.add("http://" + host + ":" + QDRANT_REST_PORT);
            // If user provided gRPC port, also try the mapped REST port
            if (port == QDRANT_GRPC_PORT) {
                bases.add("http://" + host + ":" + QDRANT_GRPC_PORT); // last resort if someone exposed REST there
            } else if (port == DOCKER_GRPC_PORT) {
                bases.add("http://" + host + ":" + DOCKER_REST_PORT); // docker-compose mapping in this repo
            }
            // Finally, try whatever port was configured explicitly
            bases.add("http://" + host + ":" + port);
        }
        return bases;
    }

    /**
     * Creates a Qdrant index initializer with configuration defaults.
     *
     * @param appProperties application configuration
     * @param restTemplateBuilder shared RestTemplate builder for HTTP configuration
     * @param embeddingModel embedding model for dimension validation
     */
    public QdrantIndexInitializer(AppProperties appProperties, RestTemplateBuilder restTemplateBuilder,
                                  EmbeddingModel embeddingModel) {
        this.ensurePayloadIndexes = appProperties.getQdrant().isEnsurePayloadIndexes();
        this.embeddingModel = embeddingModel;
        // Build RestTemplate once and reuse for all index creation requests
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
     * Ensures key payload indexes exist after application startup and validates collection dimensions.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensurePayloadIndexes() {
        // Validate embedding dimensions match collection configuration
        validateCollectionDimensions();
        
        if (!ensurePayloadIndexes) {
            log.info("[QDRANT] Skipping payload index ensure (app.qdrant.ensure-payload-indexes=false)");
            return;
        }
        try {
            createPayloadIndex("url", SCHEMA_TYPE_KEYWORD);
            createPayloadIndex("hash", SCHEMA_TYPE_KEYWORD);
            createPayloadIndex("chunkIndex", SCHEMA_TYPE_INTEGER);
        } catch (RuntimeException indexCreationException) {
            log.warn("Unable to ensure Qdrant payload indexes (exception type: {})",
                indexCreationException.getClass().getSimpleName());
            throw new IllegalStateException("Unable to ensure Qdrant payload indexes", indexCreationException);
        }
    }

    /**
     * Validates that the Qdrant collection's vector dimensions match the embedding model dimensions.
     *
     * <p>This prevents silent failures when switching embedding models without recreating the collection.
     * A dimension mismatch will cause all similarity searches to fail.
     */
    private void validateCollectionDimensions() {
        int expectedDimensions = embeddingModel.dimensions();
        log.info("[QDRANT] Embedding model dimensions: {}", expectedDimensions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }

        String collectionPath = "/collections/" + collection;
        
        for (String base : restBaseUrls()) {
            String url = base + collectionPath;
            try {
                ResponseEntity<QdrantCollectionInfoResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), QdrantCollectionInfoResponse.class
                );

                Integer collectionDimensions = extractVectorDimensions(response.getBody());
                if (collectionDimensions != null) {
                    if (collectionDimensions != expectedDimensions) {
                        log.error("[QDRANT] DIMENSION MISMATCH: Collection '{}' has {} dimensions, "
                            + "but embedding model produces {} dimensions. "
                            + "This will cause all similarity searches to fail. "
                            + "Recreate the collection or switch back to the original embedding model.",
                            collection, collectionDimensions, expectedDimensions);
                        // Log warning but don't fail startup - let the application try to run
                        // The actual failure will happen on first search/insert
                    } else {
                        log.info("[QDRANT] Collection '{}' dimensions ({}) match embedding model",
                            collection, collectionDimensions);
                    }
                }
                return; // Success - we got a response
            } catch (RuntimeException exception) {
                log.debug("[QDRANT] Could not validate dimensions via {} ({})", 
                    url, exception.getClass().getSimpleName());
                // Continue to next URL candidate
            }
        }
        
        log.info("[QDRANT] Could not validate collection dimensions (Qdrant may be unavailable)");
    }

    /**
     * Extracts vector dimensions from Qdrant collection info response.
     */
    private Integer extractVectorDimensions(QdrantCollectionInfoResponse responseBody) {
        if (responseBody == null || responseBody.collectionInfoResult() == null) {
            return null;
        }
        QdrantCollectionConfig collectionConfig = responseBody.collectionInfoResult().collectionConfig();
        if (collectionConfig == null || collectionConfig.collectionParams() == null) {
            return null;
        }
        JsonNode vectorsNode = collectionConfig.collectionParams().vectorsNode();
        if (vectorsNode == null || vectorsNode.isNull()) {
            return null;
        }
        Integer directSize = extractVectorSize(vectorsNode);
        if (directSize != null) {
            return directSize;
        }
        if (!vectorsNode.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> entry : vectorsNode.properties()) {
            Integer namedSize = extractVectorSize(entry.getValue());
            if (namedSize != null) {
                return namedSize;
            }
        }
        return null;
    }

    private Integer extractVectorSize(JsonNode vectorsNode) {
        if (vectorsNode == null || vectorsNode.isNull()) {
            return null;
        }
        JsonNode sizeNode = vectorsNode.get("size");
        if (sizeNode != null && sizeNode.isNumber()) {
            return sizeNode.intValue();
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantCollectionInfoResponse(
        @JsonProperty("result") QdrantCollectionInfoResult collectionInfoResult
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantCollectionInfoResult(
        @JsonProperty("config") QdrantCollectionConfig collectionConfig
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantCollectionConfig(
        @JsonProperty("params") QdrantCollectionParams collectionParams
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantCollectionParams(
        @JsonProperty("vectors") JsonNode vectorsNode
    ) {}

    private void createPayloadIndex(String field, String schemaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Connection", "close"); // Prevent connection reuse issues
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        
        // Qdrant API requires field_schema as an object: {"type": "keyword"} not just "keyword"
        PayloadIndexRequest body = new PayloadIndexRequest(field, Map.of("type", schemaType));

        // Qdrant payload index endpoint (official API)
        String indexPath = "/collections/" + collection + "/index";

        Exception lastError = null;
        for (String base : restBaseUrls()) {
            String url = base + indexPath;
            try {
                // Use PUT for Qdrant payload index creation (official API)
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.PUT, 
                    new HttpEntity<>(body, headers), String.class);
                log.info("[QDRANT] Ensured payload index field={} (status={})", field, resp.getStatusCode().value());
                return;
            } catch (RuntimeException putEx) {
                lastError = putEx;
                log.debug("[QDRANT] PUT failed for index field={} url={} (exceptionType={})",
                    field, url, putEx.getClass().getSimpleName());
                // Continue to next URL candidate if available
            }
        }
        // If we reach here, all attempts failed; log once at INFO to avoid noisy warnings.
        log.info("[QDRANT] Could not ensure payload index field={}. Last error type: {}",
                field, lastError != null ? lastError.getClass().getSimpleName() : "unknown");
    }

    /**
     * Request body for Qdrant payload index creation.
     * 
     * @param fieldName the field to index
     * @param fieldSchema schema object containing type, e.g., {"type": "keyword"}
     */
    private record PayloadIndexRequest(
        @JsonProperty("field_name") String fieldName,
        @JsonProperty("field_schema") Map<String, String> fieldSchema
    ) {
    }
}
