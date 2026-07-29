package com.williamcallahan.javachat.service;

import io.netty.channel.ConnectTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Embedding client that calls a local embedding provider without fallbacks.
 *
 * <p>This implementation fails fast when the provider is unreachable or returns invalid
 * responses so ingestion and retrieval never cache synthetic vectors.</p>
 */
public final class LocalEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingClient.class);

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;
    private final RestTemplateBuilder restTemplateBuilder;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int DEADLINE_CONNECT_BUDGET_DIVISOR = 4;
    private static final long MINIMUM_TRANSPORT_BUDGET_MILLIS = 2;
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
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.dimensions = dimensions;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        this.restTemplateBuilder = Objects.requireNonNull(restTemplateBuilder, "restTemplateBuilder");
    }

    @Override
    public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
        Objects.requireNonNull(requestTier, "requestTier");
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return fetchValidatedEmbeddings(texts, configuredOperationTimeout(texts.size()));
    }

    /**
     * Bounds the embedding call by the tighter of the caller budget and the configured socket timeouts.
     *
     * <p>The local provider's {@code RestTemplate} socket timeouts are fixed at build time, so a
     * caller-owned stage budget tighter than those defaults is applied by deriving a per-call
     * template whose connect and read timeouts cannot exceed the remaining budget.</p>
     */
    @Override
    public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier, Duration requestTimeout) {
        Objects.requireNonNull(requestTier, "requestTier");
        Duration requiredRequestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (requiredRequestTimeout.isNegative() || requiredRequestTimeout.isZero()) {
            throw localDeadlineFailure();
        }
        return fetchValidatedEmbeddings(texts, requiredRequestTimeout);
    }

    @Override
    public void warmUp() {
        fetchValidatedEmbeddings(List.of(EMBEDDING_WARM_UP_PROBE_TEXT), configuredOperationTimeout(1));
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private List<float[]> fetchValidatedEmbeddings(List<String> texts, Duration operationTimeout) {
        try {
            return callEmbeddingApi(texts, operationTimeout);
        } catch (org.springframework.web.client.RestClientResponseException apiException) {
            String details = formatHttpFailure(apiException);
            throw new EmbeddingServiceUnavailableException(details, apiException);
        } catch (ResourceAccessException transportException) {
            String details = sanitizeMessage(transportException.getMessage());
            String failureMessage = details.isBlank()
                    ? "Local embedding transport failed against " + baseUrl
                    : "Local embedding transport failed against " + baseUrl + ": " + details;
            Throwable failureCause = transportException;
            if (containsTransportTimeoutException(transportException)) {
                TimeoutException timeoutException = new TimeoutException(failureMessage);
                timeoutException.initCause(transportException);
                failureCause = timeoutException;
            }
            throw new EmbeddingServiceTemporarilyUnavailableException(failureMessage, failureCause);
        } catch (RestClientException | IllegalStateException apiException) {
            String details = sanitizeMessage(apiException.getMessage());
            String failureMessage = details.isBlank()
                    ? "Local embedding request failed against " + baseUrl
                    : "Local embedding request failed against " + baseUrl + ": " + details;
            throw new EmbeddingServiceUnavailableException(failureMessage, apiException);
        }
    }

    private List<float[]> callEmbeddingApi(List<String> texts, Duration operationTimeout) {
        log.debug("[EMBEDDING] Generating embeddings for request payload");
        long operationDeadlineNanos = System.nanoTime() + operationTimeout.toNanos();
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int startIndex = 0; startIndex < texts.size(); startIndex += batchSize) {
            RestTemplate batchRestTemplate = budgetedRestTemplate(operationDeadlineNanos);
            int endIndex = Math.min(startIndex + batchSize, texts.size());
            List<String> batchInputTexts = List.copyOf(texts.subList(startIndex, endIndex));
            List<float[]> batchEmbeddings = fetchEmbeddingsFromApi(batchInputTexts, batchRestTemplate);
            if (batchEmbeddings.size() != batchInputTexts.size()) {
                throw new EmbeddingServiceUnavailableException(
                        "Local embedding response size mismatch for batch starting at index " + startIndex);
            }
            embeddings.addAll(batchEmbeddings);
        }
        requireRemainingOperationBudgetMillis(operationDeadlineNanos);
        log.info("Generated {} embeddings successfully", embeddings.size());
        return List.copyOf(embeddings);
    }

    private Duration configuredOperationTimeout(int textCount) {
        long batchCount = Math.max(1L, (textCount + (long) batchSize - 1L) / batchSize);
        return Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS + READ_TIMEOUT_SECONDS)
                .multipliedBy(batchCount);
    }

    private RestTemplate budgetedRestTemplate(long operationDeadlineNanos) {
        long remainingBudgetMillis = requireRemainingOperationBudgetMillis(operationDeadlineNanos);
        long connectBudgetMillis = Math.min(
                Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS).toMillis(),
                Math.max(1L, remainingBudgetMillis / DEADLINE_CONNECT_BUDGET_DIVISOR));
        long readBudgetMillis = Math.min(
                Duration.ofSeconds(READ_TIMEOUT_SECONDS).toMillis(), remainingBudgetMillis - connectBudgetMillis);
        return restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectBudgetMillis))
                .readTimeout(Duration.ofMillis(readBudgetMillis))
                .build();
    }

    private static long requireRemainingOperationBudgetMillis(long operationDeadlineNanos) {
        long remainingBudgetMillis = Duration.ofNanos(Math.max(0L, operationDeadlineNanos - System.nanoTime()))
                .toMillis();
        if (remainingBudgetMillis < MINIMUM_TRANSPORT_BUDGET_MILLIS) {
            throw localDeadlineFailure();
        }
        return remainingBudgetMillis;
    }

    private static EmbeddingServiceTemporarilyUnavailableException localDeadlineFailure() {
        String failureMessage = "Local embedding request deadline elapsed locally";
        return new EmbeddingServiceTemporarilyUnavailableException(
                failureMessage, new TimeoutException(failureMessage));
    }

    private static boolean containsTransportTimeoutException(Throwable transportException) {
        Set<Throwable> visitedCauses = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable causeCandidate = transportException;
        while (causeCandidate != null && visitedCauses.add(causeCandidate)) {
            if (causeCandidate instanceof SocketTimeoutException
                    || causeCandidate instanceof ConnectTimeoutException
                    || causeCandidate instanceof io.netty.handler.timeout.TimeoutException) {
                return true;
            }
            causeCandidate = causeCandidate.getCause();
        }
        return false;
    }

    /**
     * Fetches embeddings for one batch from the API.
     *
     * @param batchInputTexts input texts for one batch
     * @return embedding vectors matching batch input order
     * @throws EmbeddingServiceUnavailableException when the provider returns invalid data
     */
    private List<float[]> fetchEmbeddingsFromApi(List<String> batchInputTexts, RestTemplate embeddingRestTemplate) {
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

        EmbeddingResponsePayload response =
                embeddingRestTemplate.postForObject(url, entity, EmbeddingResponsePayload.class);

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
