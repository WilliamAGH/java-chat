package com.williamcallahan.javachat.service;

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
 * Embedding model that calls a local embedding provider without fallbacks.
 *
 * <p>This implementation fails fast when the provider is unreachable or returns invalid
 * responses so ingestion and retrieval never cache synthetic vectors.</p>
 */
public class LocalEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final RestTemplate restTemplate;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final int MAX_ERROR_SNIPPET = 512;

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
    }

    /**
     * Generates embeddings for the provided request and propagates provider failures.
     *
     * @param request embedding request payload
     * @return embedding response
     * @throws EmbeddingServiceUnavailableException when the provider is unreachable or returns invalid data
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null || request.getInstructions().isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        try {
            return callEmbeddingApi(request);
        } catch (org.springframework.web.client.RestClientResponseException apiException) {
            String details = formatHttpFailure(apiException);
            throw new EmbeddingServiceUnavailableException(details, apiException);
        } catch (RestClientException | IllegalStateException apiException) {
            String details = sanitizeMessage(apiException.getMessage());
            String failureMessage = details.isBlank()
                    ? "Local embedding request failed against " + baseUrl
                    : "Local embedding request failed against " + baseUrl + ": " + details;
            throw new EmbeddingServiceUnavailableException(failureMessage, apiException);
        }
    }

    /**
     * Call the embedding API for all texts in the request.
     */
    private EmbeddingResponse callEmbeddingApi(EmbeddingRequest request) {
        log.debug("[EMBEDDING] Generating embeddings for request payload");

        List<Embedding> embeddings = new ArrayList<>();

        for (String text : request.getInstructions()) {
            float[] vector = fetchEmbeddingFromApi(text);
            if (vector.length == 0) {
                throw new EmbeddingServiceUnavailableException("Local embedding response was empty");
            }
            embeddings.add(new Embedding(vector, embeddings.size()));
        }

        log.info("Generated {} embeddings successfully", embeddings.size());
        return new EmbeddingResponse(embeddings);
    }

    /**
     * Fetches a single embedding from the API.
     *
     * @param text input text to embed
     * @return embedding vector
     * @throws EmbeddingServiceUnavailableException when the provider returns invalid data
     */
    private float[] fetchEmbeddingFromApi(String text) {
        String url = baseUrl + EMBEDDINGS_PATH;

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
            throw new IllegalStateException("Local embedding response was null");
        }

        List<EmbeddingData> dataList = response.data();
        if (dataList.isEmpty()) {
            throw new IllegalStateException("Local embedding response missing embedding entries");
        }

        EmbeddingData first = dataList.get(0);
        if (first == null || first.embedding() == null || first.embedding().isEmpty()) {
            throw new IllegalStateException("Local embedding response missing embedding payload");
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
     * Returns the configured embedding dimensions.
     *
     * @return embedding dimensions
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * Embeds a single document with strict failure propagation.
     *
     * @param document document to embed
     * @return embedding vector
     * @throws EmbeddingServiceUnavailableException when the provider is unreachable or returns invalid data
     */
    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingRequest request = new EmbeddingRequest(List.of(document.getText()), null);
        EmbeddingResponse response = call(request);
        if (!response.getResults().isEmpty()) {
            return response.getResults().get(0).getOutput();
        }
        throw new EmbeddingServiceUnavailableException("Local embedding response was empty");
    }

    private static String formatHttpFailure(org.springframework.web.client.RestClientResponseException exception) {
        String payload = sanitizeMessage(exception.getResponseBodyAsString());
        if (!payload.isBlank()) {
            return "Local embedding server returned HTTP " + exception.getRawStatusCode() + ": " + payload;
        }
        return "Local embedding server returned HTTP " + exception.getRawStatusCode();
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String sanitized = message.replace("\r", " ").replace("\n", " ").trim();
        if (sanitized.length() > MAX_ERROR_SNIPPET) {
            return sanitized.substring(0, MAX_ERROR_SNIPPET) + "...";
        }
        return sanitized;
    }

    private record EmbeddingRequestPayload(String model, String input) {}

    private record EmbeddingResponsePayload(List<EmbeddingData> data) {}

    private record EmbeddingData(List<Double> embedding) {}
}
