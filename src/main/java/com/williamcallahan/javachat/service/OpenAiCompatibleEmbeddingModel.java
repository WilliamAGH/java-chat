package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple OpenAI-compatible EmbeddingModel.
 * Calls {baseUrl}/v1/embeddings with Bearer token and model name.
 * Works with OpenAI and providers like Novita that expose compatible APIs.
 */
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

    private final String baseUrl;           // e.g., https://api.openai.com/openai/v1 or provider base
    private final String apiKey;            // Bearer token
    private final String modelName;         // embedding model id
    private final int dimensionsHint;       // used only as a hint; actual vector size comes from response
    private final RestTemplate restTemplate;

    public OpenAiCompatibleEmbeddingModel(String baseUrl,
                                          String apiKey,
                                          String modelName,
                                          int dimensionsHint,
                                          RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint > 0 ? dimensionsHint : 4096;
        this.restTemplate = restTemplateBuilder
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Remote embedding API key is not configured");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Remote embedding base URL is not configured");
        }

        // Build endpoint robustly. Support users passing either a base (e.g., https://api.openai.com)
        // or a full path including /v1/embeddings. Avoid double-appending.
        String endpoint = baseUrl;
        // Strip trailing slash for normalization
        if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        if (!endpoint.endsWith("/v1/embeddings")) {
            if (endpoint.endsWith("/v1")) {
                endpoint = endpoint + "/embeddings";
            } else if (!endpoint.contains("/v1/embeddings")) {
                endpoint = endpoint + "/v1/embeddings";
            }
        }
        List<Embedding> results = new ArrayList<>();

        for (int i = 0; i < request.getInstructions().size(); i++) {
            String input = request.getInstructions().get(i);

            Map<String, Object> body = new HashMap<>();
            body.put("model", modelName);
            body.put("input", input);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.postForObject(endpoint, entity, Map.class);
                if (response == null || !response.containsKey("data")) {
                    log.warn("[EMBEDDING] Remote response missing 'data' field; falling back on zero vector");
                    results.add(new Embedding(new float[dimensions()], i));
                    continue;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data.isEmpty()) {
                    log.warn("[EMBEDDING] Remote response 'data' empty; using zero vector");
                    results.add(new Embedding(new float[dimensions()], i));
                    continue;
                }

                @SuppressWarnings("unchecked")
                List<Number> vec = (List<Number>) data.get(0).get("embedding");
                if (vec == null || vec.isEmpty()) {
                    log.warn("[EMBEDDING] Remote embedding array empty; using zero vector");
                    results.add(new Embedding(new float[dimensions()], i));
                    continue;
                }

                float[] out = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) out[j] = vec.get(j).floatValue();
                results.add(new Embedding(out, i));
            } catch (Exception e) {
                log.warn("[EMBEDDING] Remote embedding call failed: {}", e.getMessage());
                // Propagate to let GracefulEmbeddingModel trigger fallback
                throw e;
            }
        }

        return new EmbeddingResponse(results);
    }

    @Override
    public int dimensions() {
        return dimensionsHint;
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingRequest req = new EmbeddingRequest(List.of(document.getText()), null);
        EmbeddingResponse res = call(req);
        return res.getResults().isEmpty() ? new float[dimensions()] : res.getResults().get(0).getOutput();
    }
}
