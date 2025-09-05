package com.williamcallahan.javachat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class QdrantIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(QdrantIndexInitializer.class);

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
            bases.add("http://" + host + ":6333");
            // If user provided gRPC port, also try the mapped REST port
            if (port == 6334) {
                bases.add("http://" + host + ":6334"); // last resort if someone exposed REST there
            } else if (port == 8086) {
                bases.add("http://" + host + ":8087"); // docker-compose mapping in this repo
            }
            // Finally, try whatever port was configured explicitly
            bases.add("http://" + host + ":" + port);
        }
        return bases;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensurePayloadIndexes() {
        try {
            createPayloadIndex("url", "keyword");
            createPayloadIndex("hash", "keyword");
            createPayloadIndex("chunkIndex", "integer");
        } catch (Exception e) {
            log.warn("Unable to ensure Qdrant payload indexes (will continue): {}", e.getMessage());
        }
    }

    private void createPayloadIndex(String field, String schema) {
        // Create RestTemplate with longer timeouts for Qdrant Cloud
        // Using connectTimeout and readTimeout (new API since Spring Boot 3.4)
        RestTemplate rt = new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
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
        Map<String, Object> body = Map.of(
                "field_name", field,
                "field_schema", schema
        );

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
                    ResponseEntity<String> resp = rt.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
                    log.info("[QDRANT] Ensured payload index '{}' (schema={}) via PUT {} (status={})",
                            field, schema, url, resp.getStatusCode());
                    return;
                } catch (Exception putEx) {
                    lastError = putEx;
                    log.debug("[QDRANT] PUT failed for index '{}': {}", field, putEx.getMessage());
                    // Continue to next URL candidate if available
                }
            }
        }
        // If we reach here, all attempts failed; log once at INFO to avoid noisy warnings.
        log.info("[QDRANT] Could not ensure payload index '{}' (schema={}). Last error: {}", field, schema,
                lastError != null ? lastError.getMessage() : "unknown");
    }
}

