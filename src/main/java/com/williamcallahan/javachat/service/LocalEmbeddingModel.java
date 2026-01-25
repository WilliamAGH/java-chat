package com.williamcallahan.javachat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Embedding model wrapper that calls a local service with safe fallbacks.
 */
public class LocalEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final RestTemplate restTemplate;
    private boolean serverAvailable = true;
    private long lastCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 60_000L;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int FALLBACK_DIMENSION_LIMIT = 100;
    private static final float FALLBACK_SCALE = 0.1f;
    private static final int BYTE_MAX = 255;
    private static final float BYTE_MIDPOINT = 0.5f;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final float[] EMPTY_VECTOR = new float[0];

    /**
     * Creates a local embedding model backed by the configured service endpoint.
     *
     * @param baseUrl local embedding base URL
     * @param modelName embedding model name
     * @param dimensions embedding vector dimensions
     * @param restTemplateBuilder RestTemplate builder
     */
    public LocalEmbeddingModel(
            String baseUrl, String modelName, int dimensions, RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
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
                log.info("[EMBEDDING] Local embedding server is now available");
            }
            serverAvailable = true;
        } catch (RestClientException healthCheckException) {
            if (serverAvailable) {
                log.warn("[EMBEDDING] Local embedding server not reachable. Using fallback embeddings.");
            }
            serverAvailable = false;
        }
    }

    /**
     * Generates embeddings for the provided request, falling back when needed.
     *
     * @param request embedding request payload
     * @return embedding response
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        checkServerAvailability();

        if (!serverAvailable) {
            return createFallbackResponse(request);
        }

        try {
            return callEmbeddingApi(request);
        } catch (RestClientException | IllegalStateException apiException) {
            return handleApiFailure(apiException, request);
        }
    }

    /**
     * Create fallback embeddings when server is unavailable.
     */
    private EmbeddingResponse createFallbackResponse(EmbeddingRequest request) {
        if (log.isTraceEnabled()) {
            log.trace(
                    "[EMBEDDING] Server unavailable, returning fallback embeddings for {} texts",
                    request.getInstructions().size());
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            String text = request.getInstructions().get(i);
            embeddings.add(new Embedding(createFallbackEmbedding(text), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /**
     * Call the embedding API for all texts in the request.
     */
    private EmbeddingResponse callEmbeddingApi(EmbeddingRequest request) {
        log.debug("[EMBEDDING] Generating embeddings for request payload");

        List<Embedding> embeddings = new ArrayList<>();

        for (String text : request.getInstructions()) {
            float[] vector = fetchEmbeddingFromApi(text);
            if (vector.length > 0) {
                embeddings.add(new Embedding(vector, embeddings.size()));
            } else {
                embeddings.add(new Embedding(createFallbackEmbedding(text), embeddings.size()));
            }
        }

        log.info("Generated {} embeddings successfully", embeddings.size());
        return new EmbeddingResponse(embeddings);
    }

    /**
     * Fetch a single embedding from the API.
     * Returns null if the API response is invalid.
     */
    private float[] fetchEmbeddingFromApi(String text) {
        String url = baseUrl + "/v1/embeddings";

        EmbeddingRequestPayload requestBody = new EmbeddingRequestPayload(modelName, text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<EmbeddingRequestPayload> entity = new HttpEntity<>(requestBody, headers);

        log.debug("[EMBEDDING] Calling embedding API");

        EmbeddingResponsePayload response = restTemplate.postForObject(url, entity, EmbeddingResponsePayload.class);

        return parseEmbeddingResponse(response);
    }

    /**
     * Parse the embedding vector from the API response.
     */
    private float[] parseEmbeddingResponse(EmbeddingResponsePayload response) {
        if (response == null || response.data() == null) {
            log.error("Invalid response from embedding API");
            return EMPTY_VECTOR;
        }

        List<EmbeddingData> dataList = response.data();
        if (dataList.isEmpty()) {
            log.error("Empty data list in embedding API response");
            return EMPTY_VECTOR;
        }

        EmbeddingData first = dataList.get(0);
        if (first == null || first.embedding() == null || first.embedding().isEmpty()) {
            log.error("Empty embedding payload in embedding API response");
            return EMPTY_VECTOR;
        }

        List<Double> embeddingList = first.embedding();
        float[] vector = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            vector[i] = embeddingList.get(i).floatValue();
        }

        log.debug("Retrieved embedding vector of dimension: {}", vector.length);
        return vector;
    }

    /**
     * Handle API failure by marking server unavailable and returning fallback embeddings.
     */
    private EmbeddingResponse handleApiFailure(RuntimeException exception, EmbeddingRequest request) {
        log.warn(
                "[EMBEDDING] Failed to get embeddings from server, using fallback (exceptionType={})",
                exception.getClass().getName());
        serverAvailable = false;
        lastCheckTime = System.currentTimeMillis();

        // Return fallback embeddings for ALL texts (not partial results)
        return createFallbackResponse(request);
    }

    private float[] createFallbackEmbedding(String text) {
        // Create a simple deterministic embedding based on text hash
        // This is not semantically meaningful but allows the app to continue
        float[] vector = new float[dimensions];
        if (text != null && !text.isEmpty()) {
            byte[] hashBytes = hashText(text);
            int limit = Math.min(dimensions, FALLBACK_DIMENSION_LIMIT);
            for (int index = 0; index < limit; index++) {
                int byteIndex = index % hashBytes.length;
                int normalizedByte = Byte.toUnsignedInt(hashBytes[byteIndex]);
                float centered = (normalizedByte / (float) BYTE_MAX) - BYTE_MIDPOINT;
                vector[index] = centered * FALLBACK_SCALE;
            }
        }
        return vector;
    }

    private byte[] hashText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException hashException) {
            throw new IllegalStateException("Missing hash algorithm: " + HASH_ALGORITHM, hashException);
        }
    }

    /**
     * Returns the configured embedding dimensions.
     *
     * @return embedding dimensions
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * Embeds a single document, falling back if the local service is unavailable.
     *
     * @param document document to embed
     * @return embedding vector
     */
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

    private record EmbeddingRequestPayload(String model, String input) {}

    private record EmbeddingResponsePayload(List<EmbeddingData> data) {}

    private record EmbeddingData(List<Double> embedding) {}
}
