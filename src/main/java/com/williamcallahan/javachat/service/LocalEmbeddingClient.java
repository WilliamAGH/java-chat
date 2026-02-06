package com.williamcallahan.javachat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Embedding client that calls a local embedding provider without fallbacks.
 *
 * <p>This implementation fails fast when the provider is unreachable or returns invalid
 * responses so ingestion and retrieval never cache synthetic vectors.</p>
 */
public class LocalEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingClient.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;
    private final RestTemplate restTemplate;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final int MAX_ERROR_SNIPPET = 512;

    /**
     * Creates a local embedding client backed by the configured service endpoint.
     *
     * @param baseUrl local embedding base URL
     * @param modelName embedding model name
     * @param dimensions embedding vector dimensions
     * @param batchSize embedding request batch size
     * @param restTemplateBuilder RestTemplate builder
     */
    public LocalEmbeddingClient(
            String baseUrl, String modelName, int dimensions, int batchSize, RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .readTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            return callEmbeddingApi(texts);
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

    private List<float[]> callEmbeddingApi(List<String> texts) {
        log.debug("[EMBEDDING] Generating embeddings for request payload");
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int startIndex = 0; startIndex < texts.size(); startIndex += batchSize) {
            int endIndex = Math.min(startIndex + batchSize, texts.size());
            List<String> batchInputTexts = List.copyOf(texts.subList(startIndex, endIndex));
            List<float[]> batchEmbeddings = fetchEmbeddingsFromApi(batchInputTexts);
            if (batchEmbeddings.size() != batchInputTexts.size()) {
                throw new EmbeddingServiceUnavailableException(
                        "Local embedding response size mismatch for batch starting at index " + startIndex);
            }
            embeddings.addAll(batchEmbeddings);
        }
        log.info("Generated {} embeddings successfully", embeddings.size());
        return List.copyOf(embeddings);
    }

    /**
     * Fetches embeddings for one batch from the API.
     *
     * @param batchInputTexts input texts for one batch
     * @return embedding vectors matching batch input order
     * @throws EmbeddingServiceUnavailableException when the provider returns invalid data
     */
    private List<float[]> fetchEmbeddingsFromApi(List<String> batchInputTexts) {
        Objects.requireNonNull(batchInputTexts, "batchInputTexts");
        if (batchInputTexts.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + EMBEDDINGS_PATH;

        EmbeddingBatchRequestPayload requestBody = new EmbeddingBatchRequestPayload(modelName, batchInputTexts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<EmbeddingBatchRequestPayload> entity = new HttpEntity<>(requestBody, headers);

        log.debug("[EMBEDDING] Calling embedding API batch with {} texts", batchInputTexts.size());

        EmbeddingResponsePayload response = restTemplate.postForObject(url, entity, EmbeddingResponsePayload.class);

        return parseEmbeddingResponse(response, batchInputTexts.size());
    }

    /**
     * Parse the embedding vector from the API response.
     */
    private List<float[]> parseEmbeddingResponse(EmbeddingResponsePayload response, int expectedCount) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Local embedding response was null");
        }

        List<EmbeddingVectorData> embeddingEntries = response.data();
        if (embeddingEntries.isEmpty()) {
            throw new IllegalStateException("Local embedding response missing embedding entries");
        }

        List<float[]> embeddingsByIndex = new ArrayList<>(expectedCount);
        for (int slotIndex = 0; slotIndex < expectedCount; slotIndex++) {
            embeddingsByIndex.add(null);
        }

        for (int entryIndex = 0; entryIndex < embeddingEntries.size(); entryIndex++) {
            EmbeddingVectorData embeddingEntry = embeddingEntries.get(entryIndex);
            if (embeddingEntry == null) {
                throw new IllegalStateException("Local embedding response contained null entry at index " + entryIndex);
            }
            int targetIndex = resolveTargetIndex(entryIndex, embeddingEntry.index(), expectedCount);
            if (embeddingsByIndex.get(targetIndex) != null) {
                throw new IllegalStateException("Local embedding response contained duplicate index " + targetIndex);
            }
            float[] embeddingVector = toEmbeddingVector(embeddingEntry.embedding(), targetIndex);
            embeddingsByIndex.set(targetIndex, embeddingVector);
        }

        List<float[]> orderedEmbeddings = new ArrayList<>(expectedCount);
        for (int expectedIndex = 0; expectedIndex < expectedCount; expectedIndex++) {
            float[] embeddingVector = embeddingsByIndex.get(expectedIndex);
            if (embeddingVector == null) {
                throw new IllegalStateException(
                        "Local embedding response missing embedding for index " + expectedIndex);
            }
            orderedEmbeddings.add(embeddingVector);
        }
        return List.copyOf(orderedEmbeddings);
    }

    private int resolveTargetIndex(int fallbackIndex, Integer declaredIndex, int expectedCount) {
        int targetIndex = declaredIndex == null ? fallbackIndex : declaredIndex;
        if (targetIndex < 0 || targetIndex >= expectedCount) {
            throw new IllegalStateException("Local embedding response index out of bounds: " + targetIndex
                    + " (expectedCount=" + expectedCount + ")");
        }
        return targetIndex;
    }

    private float[] toEmbeddingVector(List<Double> embeddingValues, int embeddingIndex) {
        if (embeddingValues == null || embeddingValues.isEmpty()) {
            throw new IllegalStateException(
                    "Local embedding response missing embedding payload for index " + embeddingIndex);
        }
        if (embeddingValues.size() != dimensions) {
            throw new EmbeddingServiceUnavailableException("Local embedding dimension mismatch at index "
                    + embeddingIndex + ": expected " + dimensions + " but received " + embeddingValues.size());
        }

        float[] embeddingVector = new float[embeddingValues.size()];
        for (int valueIndex = 0; valueIndex < embeddingValues.size(); valueIndex++) {
            Double embeddingValue = embeddingValues.get(valueIndex);
            if (embeddingValue == null) {
                throw new IllegalStateException("Local embedding value was null at index " + valueIndex);
            }
            embeddingVector[valueIndex] = embeddingValue.floatValue();
        }

        log.debug("Retrieved embedding vector of dimension: {}", embeddingVector.length);
        return embeddingVector;
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

    private static String formatHttpFailure(org.springframework.web.client.RestClientResponseException exception) {
        String payload = sanitizeMessage(exception.getResponseBodyAsString());
        if (!payload.isBlank()) {
            return "Local embedding server returned HTTP "
                    + exception.getStatusCode().value() + ": " + payload;
        }
        return "Local embedding server returned HTTP "
                + exception.getStatusCode().value();
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

    private record EmbeddingBatchRequestPayload(String model, List<String> input) {}

    private record EmbeddingResponsePayload(List<EmbeddingVectorData> data) {}

    private record EmbeddingVectorData(Integer index, List<Double> embedding) {}
}
