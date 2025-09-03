package com.williamcallahan.javachat.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocalEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);
    
    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final RestTemplate restTemplate;
    private boolean serverAvailable = true;
    private long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 60000; // Re-check every minute
    
    public LocalEmbeddingModel(String baseUrl, String modelName, int dimensions, RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.restTemplate = restTemplateBuilder
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build();
        // Check server availability on startup
        checkServerAvailability();
    }
    
    private void checkServerAvailability() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) {
            return; // Don't check too frequently
        }
        lastCheckTime = now;
        
        try {
            String healthUrl = baseUrl + "/v1/models";
            restTemplate.getForObject(healthUrl, String.class);
            if (!serverAvailable) {
                log.info("[EMBEDDING] Local embedding server is now available at {}", baseUrl);
            }
            serverAvailable = true;
        } catch (Exception e) {
            if (serverAvailable) {
                log.warn("[EMBEDDING] Local embedding server not reachable at {}. Using fallback embeddings (this message appears once).", baseUrl);
            }
            serverAvailable = false;
        }
    }
    
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        // Periodically re-check server availability
        checkServerAvailability();
        
        List<Embedding> embeddings = new ArrayList<>();
        
        // If server is not available, return deterministic fallback embeddings
        if (!serverAvailable) {
            // Use TRACE level to avoid spam during document processing
            if (log.isTraceEnabled()) {
                log.trace("[EMBEDDING] Server unavailable, returning fallback embeddings for {} texts", 
                    request.getInstructions().size());
            }
            for (int i = 0; i < request.getInstructions().size(); i++) {
                // Create a deterministic but simple embedding based on text hash
                String text = request.getInstructions().get(i);
                float[] vector = createFallbackEmbedding(text);
                embeddings.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(embeddings);
        }
        
        // Server is available, try to get real embeddings
        try {
            log.debug("[EMBEDDING] Generating embeddings for {} texts using model: {}", 
                request.getInstructions().size(), modelName);
            
            for (String text : request.getInstructions()) {
                // Call LM Studio OpenAI-compatible API
                String url = baseUrl + "/v1/embeddings";
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("input", text);
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
                
                log.debug("[EMBEDDING] Calling API at: {} for text of length: {} chars", url, text.length());
                
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
                
                if (response != null && response.containsKey("data")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
                    if (!dataList.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<Double> embeddingList = (List<Double>) dataList.get(0).get("embedding");
                        
                        float[] vector = new float[embeddingList.size()];
                        for (int i = 0; i < embeddingList.size(); i++) {
                            vector[i] = embeddingList.get(i).floatValue();
                        }
                        
                        log.debug("Retrieved embedding vector of dimension: {}", vector.length);
                        embeddings.add(new Embedding(vector, embeddings.size()));
                    }
                } else {
                    log.error("Invalid response from embedding API: {}", response);
                    // Fallback to deterministic vector
                    embeddings.add(new Embedding(createFallbackEmbedding(text), embeddings.size()));
                }
            }
            
            log.info("Generated {} embeddings successfully", embeddings.size());
            return new EmbeddingResponse(embeddings);
        } catch (Exception e) {
            // Mark server as unavailable and return fallback embeddings
            log.warn("[EMBEDDING] Failed to get embeddings from server, using fallback: {}", e.getMessage());
            serverAvailable = false;
            lastCheckTime = System.currentTimeMillis();
            
            // Return fallback embeddings instead of throwing exception
            for (int i = 0; i < request.getInstructions().size(); i++) {
                String text = request.getInstructions().get(i);
                embeddings.add(new Embedding(createFallbackEmbedding(text), i));
            }
            return new EmbeddingResponse(embeddings);
        }
    }
    
    private float[] createFallbackEmbedding(String text) {
        // Create a simple deterministic embedding based on text hash
        // This is not semantically meaningful but allows the app to continue
        float[] vector = new float[dimensions];
        if (text != null && !text.isEmpty()) {
            int hash = text.hashCode();
            java.util.Random rand = new java.util.Random(hash);
            for (int i = 0; i < Math.min(dimensions, 100); i++) {
                vector[i] = (rand.nextFloat() - 0.5f) * 0.1f; // Small values
            }
        }
        return vector;
    }
    
    @Override
    public int dimensions() {
        return dimensions;
    }
    
    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingRequest request = new EmbeddingRequest(List.of(document.getText()), null);
        EmbeddingResponse response = call(request);
        if (!response.getResults().isEmpty()) {
            return response.getResults().get(0).getOutput();
        }
        log.warn("Failed to embed document, returning fallback vector");
        return createFallbackEmbedding(document.getText());
    }
}