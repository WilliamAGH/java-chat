package com.williamcallahan.javachat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class QdrantIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(QdrantIndexInitializer.class);

    @Value("${spring.ai.vectorstore.qdrant.host}")
    private String host;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name}")
    private String collection;

    private String baseUrl() {
        if (useTls) {
            return "https://" + host;
        }
        return "http://" + host + ":" + port;
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
        String url = baseUrl() + "/collections/" + collection + "/indexes";
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }
        Map<String, Object> body = Map.of(
                "field_name", field,
                "field_schema", schema
        );
        try {
            rt.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            log.info("[QDRANT] Ensured payload index '{}' (schema={})", field, schema);
        } catch (Exception e) {
            // If it already exists or API variant differs, log and continue
            log.info("[QDRANT] Index '{}' may already exist or API returned non-200: {}", field, e.getMessage());
        }
    }
}

