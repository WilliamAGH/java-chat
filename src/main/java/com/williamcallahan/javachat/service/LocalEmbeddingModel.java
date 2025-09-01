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
    
    public LocalEmbeddingModel(String baseUrl, String modelName, int dimensions, RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.restTemplate = restTemplateBuilder.build();
    }
    
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            log.info("[EMBEDDING] Generating embeddings for {} texts using model: {}", 
                request.getInstructions().size(), modelName);
            
            List<Embedding> embeddings = new ArrayList<>();
            
            for (String text : request.getInstructions()) {
                // Call LM Studio OpenAI-compatible API
                String url = baseUrl + "/v1/embeddings";
                
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("input", text);
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
                
                log.info("[EMBEDDING] Calling API at: {} for text of length: {} chars", url, text.length());
                
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
                    // Fallback to zero vector
                    float[] vector = new float[dimensions];
                    embeddings.add(new Embedding(vector, embeddings.size()));
                }
            }
            
            log.info("Generated {} embeddings successfully", embeddings.size());
            return new EmbeddingResponse(embeddings);
        } catch (Exception e) {
            log.error("Failed to get embeddings from LM Studio", e);
            throw new RuntimeException("Failed to get embeddings", e);
        }
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
        log.warn("Failed to embed document, returning zero vector");
        return new float[dimensions];
    }
}