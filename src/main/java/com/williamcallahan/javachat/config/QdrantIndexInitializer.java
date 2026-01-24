package com.williamcallahan.javachat.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.client.RestTemplate;

/**
 * Initializes Qdrant payload indexes on startup to accelerate lookups.
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
     */
    public QdrantIndexInitializer(AppProperties appProperties) {
        this.ensurePayloadIndexes = appProperties.getQdrant().isEnsurePayloadIndexes();
    }

    /**
     * Ensures key payload indexes exist after application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensurePayloadIndexes() {
        if (!ensurePayloadIndexes) {
            log.info("[QDRANT] Skipping payload index ensure (app.qdrant.ensure-payload-indexes=false)");
            return;
        }
        try {
            createPayloadIndex("url", "keyword");
            createPayloadIndex("hash", "keyword");
            createPayloadIndex("chunkIndex", "integer");
        } catch (RuntimeException indexCreationException) {
            log.warn("Unable to ensure Qdrant payload indexes (exception type: {})",
                indexCreationException.getClass().getSimpleName());
            throw new IllegalStateException("Unable to ensure Qdrant payload indexes", indexCreationException);
        }
    }

    private void createPayloadIndex(String field, String schema) {
        // Create RestTemplate with longer timeouts for Qdrant Cloud
        RestTemplate rt = new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .build();
        
        // Add connection management
        rt.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("Connection", "close");
            return execution.execute(request, body);
        });
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Connection", "close"); // Prevent connection reuse issues
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        PayloadIndexRequest body = new PayloadIndexRequest(field, schema);

        // Qdrant payload index endpoint (official API)
        String[] pathCandidates = new String[] {
                "/collections/" + collection + "/index"          // official Qdrant API endpoint
        };

        Exception lastError = null;
        for (String base : restBaseUrls()) {
            for (String path : pathCandidates) {
                String url = base + path;
                try {
                    // Use PUT for Qdrant payload index creation (official API)
                    ResponseEntity<String> resp = rt.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers),
                        String.class);
                    log.info("[QDRANT] Ensured payload index (status={})", resp.getStatusCode().value());
                    return;
                } catch (RuntimeException putEx) {
                    lastError = putEx;
                    log.debug("[QDRANT] PUT failed for index (exceptionType={})",
                        putEx.getClass().getSimpleName());
                    // Continue to next URL candidate if available
                }
            }
        }
        // If we reach here, all attempts failed; log once at INFO to avoid noisy warnings.
        log.info("[QDRANT] Could not ensure payload index. Last error type: {}",
                lastError != null ? lastError.getClass().getSimpleName() : "unknown");
    }

    private record PayloadIndexRequest(
        @JsonProperty("field_name") String fieldName,
        @JsonProperty("field_schema") String fieldSchema
    ) {
    }
}
