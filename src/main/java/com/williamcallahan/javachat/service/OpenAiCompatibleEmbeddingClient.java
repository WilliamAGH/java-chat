package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-compatible embedding client that fails fast on provider errors.
 *
 * <p>Uses the OpenAI Java SDK to call `/embeddings` against the configured base URL and
 * propagates HTTP failures so invalid embeddings are never cached.</p>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int MAX_ERROR_SNIPPET = 512;
    private static final int MAX_EMBED_ATTEMPTS = 4;
    private static final long INITIAL_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_RETRY_BACKOFF_MILLIS = 8_000L;

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_TOO_EARLY = 425;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    private final OpenAIClient client;
    private final String modelName;
    private final int dimensionsHint;

    /**
     * Creates an OpenAI-compatible embedding client backed by a remote REST API endpoint.
     *
     * @param baseUrl base URL for the embedding API
     * @param apiKey API key for the embedding provider
     * @param modelName model identifier for embeddings
     * @param dimensionsHint expected embedding dimensions (used as a hint)
     * @return embedding client configured for the remote endpoint
     */
    public static OpenAiCompatibleEmbeddingClient create(
            String baseUrl, String apiKey, String modelName, int dimensionsHint) {
        validateDimensions(dimensionsHint);
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(requireConfiguredApiKey(apiKey))
                .baseUrl(normalizeSdkBaseUrl(baseUrl))
                .build();
        return new OpenAiCompatibleEmbeddingClient(client, requireConfiguredModel(modelName), dimensionsHint);
    }

    static OpenAiCompatibleEmbeddingClient create(OpenAIClient client, String modelName, int dimensionsHint) {
        validateDimensions(dimensionsHint);
        return new OpenAiCompatibleEmbeddingClient(
                Objects.requireNonNull(client, "client"), requireConfiguredModel(modelName), dimensionsHint);
    }

    OpenAiCompatibleEmbeddingClient(OpenAIClient client, String modelName, int dimensionsHint) {
        this.client = client;
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        EmbeddingRequest request = new EmbeddingRequest(modelName, texts);

        for (int attemptNumber = 1; attemptNumber <= MAX_EMBED_ATTEMPTS; attemptNumber++) {
            try {
                return executeEmbeddingRequest(request);
            } catch (Exception exception) {
                if (shouldRetry(exception, attemptNumber)) {
                    logRetryWarning(exception, attemptNumber);
                    sleepBeforeRetry(calculateBackoff(attemptNumber));
                    continue;
                }
                throw new EmbeddingServiceUnavailableException(
                        formatFailureMessage(exception, attemptNumber), exception);
            }
        }
        throw new EmbeddingServiceUnavailableException("Remote embedding call failed after retries");
    }

    private List<float[]> executeEmbeddingRequest(EmbeddingRequest request) {
        CreateEmbeddingResponse response = client.embeddings().create(request.params(), request.options());
        return parseResponse(response, request.texts().size());
    }

    private boolean shouldRetry(Exception exception, int attemptNumber) {
        if (attemptNumber >= MAX_EMBED_ATTEMPTS) {
            return false;
        }

        if (exception instanceof OpenAIServiceException serviceException) {
            return shouldRetryServiceError(
                    serviceException.statusCode(), sanitizeMessage(serviceException.getMessage()));
        } else if (exception instanceof OpenAIRetryableException) {
            return true;
        } else if (exception instanceof EmbeddingServiceUnavailableException embeddingException) {
            return shouldRetryResponseValidationError(sanitizeMessage(embeddingException.getMessage()));
        } else {
            return false;
        }
    }

    private void logRetryWarning(Exception exception, int attemptNumber) {
        String details = sanitizeMessage(exception.getMessage());
        String exceptionType = exception.getClass().getSimpleName();

        log.warn(
                "[EMBEDDING] {} on attempt {}/{}; retrying in {}ms ({})",
                exceptionType,
                attemptNumber,
                MAX_EMBED_ATTEMPTS,
                calculateBackoff(attemptNumber),
                details.isBlank() ? "no details" : details);
    }

    private long calculateBackoff(int attemptNumber) {
        return Math.min(INITIAL_RETRY_BACKOFF_MILLIS * (1L << (attemptNumber - 1)), MAX_RETRY_BACKOFF_MILLIS);
    }

    private String formatFailureMessage(Exception exception, int attemptNumber) {
        String details = sanitizeMessage(exception.getMessage());

        if (details.isBlank()) {
            return "Remote embedding call failed after " + attemptNumber + " attempt(s)";
        }
        return "Remote embedding call failed after " + attemptNumber + " attempt(s): " + details;
    }

    private record EmbeddingRequest(String modelName, List<String> texts) {
        EmbeddingCreateParams params() {
            return EmbeddingCreateParams.builder()
                    .model(modelName)
                    .inputOfArrayOfStrings(texts)
                    .build();
        }

        RequestOptions options() {
            return RequestOptions.builder().timeout(embeddingTimeout()).build();
        }

        private Timeout embeddingTimeout() {
            Duration connectTimeout = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS);
            Duration requestTimeout = Duration.ofSeconds(READ_TIMEOUT_SECONDS);
            return Timeout.builder()
                    .connect(connectTimeout)
                    .request(requestTimeout)
                    .read(requestTimeout)
                    .build();
        }
    }

    private static boolean shouldRetryServiceError(int statusCode, String details) {
        if (statusCode == HTTP_TOO_MANY_REQUESTS
                || statusCode >= HTTP_INTERNAL_SERVER_ERROR
                || statusCode == HTTP_REQUEST_TIMEOUT
                || statusCode == HTTP_CONFLICT
                || statusCode == HTTP_TOO_EARLY) {
            return true;
        }
        // Some providers intermittently return HTTP 400 with null/empty payloads for transient gateway failures.
        if (statusCode != HTTP_BAD_REQUEST) {
            return false;
        }
        String normalizedDetails = details == null ? "" : details.trim().toLowerCase(Locale.ROOT);
        return normalizedDetails.isBlank() || "null".equals(normalizedDetails) || "400: null".equals(normalizedDetails);
    }

    private static boolean shouldRetryResponseValidationError(String validationFailureMessage) {
        String normalizedMessage = validationFailureMessage == null
                ? ""
                : validationFailureMessage.trim().toLowerCase(Locale.ROOT);
        if (normalizedMessage.isBlank()) {
            return true;
        }
        return normalizedMessage.contains("response was null")
                || normalizedMessage.contains("missing embedding entries")
                || normalizedMessage.contains("missing embedding for index")
                || normalizedMessage.contains("contained null entry")
                || normalizedMessage.contains("null embedding value at index");
    }

    private static void sleepBeforeRetry(long retryBackoffMillis) {
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding retry interrupted", interruptedException);
        }
    }

    private List<float[]> parseResponse(CreateEmbeddingResponse response, int expectedCount) {
        if (response == null) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response was null");
        }
        List<com.openai.models.embeddings.Embedding> embeddingEntries = response.data();
        if (embeddingEntries.isEmpty()) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response missing embedding entries");
        }

        float[][] embeddingsByIndex = new float[expectedCount][];

        for (int itemIndex = 0; itemIndex < embeddingEntries.size(); itemIndex++) {
            com.openai.models.embeddings.Embedding embeddingEntry = embeddingEntries.get(itemIndex);
            if (embeddingEntry == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response contained null entry at index " + itemIndex);
            }
            int targetIndex = safeEmbeddingIndex(itemIndex, embeddingEntry, expectedCount);
            if (targetIndex < 0 || targetIndex >= expectedCount) {
                continue;
            }
            embeddingsByIndex[targetIndex] = toFloatVector(embeddingEntry.embedding());
        }

        List<float[]> orderedEmbeddings = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            if (embeddingsByIndex[index] == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response missing embedding for index " + index);
            }
            orderedEmbeddings.add(embeddingsByIndex[index]);
        }

        return List.copyOf(orderedEmbeddings);
    }

    private int safeEmbeddingIndex(
            int fallbackIndex, com.openai.models.embeddings.Embedding embedding, int expectedCount) {
        long responseIndex =
                embedding._index().asNumber().map(Number::longValue).orElse((long) fallbackIndex);
        if (responseIndex < 0 || responseIndex > Integer.MAX_VALUE) {
            log.debug("[EMBEDDING] Ignoring out-of-range embedding index={}", responseIndex);
            return -1;
        }
        int index = (int) responseIndex;
        if (index >= expectedCount) {
            log.debug("[EMBEDDING] Ignoring embedding index={} (expectedCount={})", index, expectedCount);
            return -1;
        }
        return index;
    }

    /**
     * Returns the configured dimension hint for downstream vector store setup.
     */
    @Override
    public int dimensions() {
        return dimensionsHint;
    }

    private float[] toFloatVector(List<Float> embeddingEntries) {
        if (embeddingEntries == null || embeddingEntries.isEmpty()) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response missing embedding values");
        }
        if (embeddingEntries.size() != dimensionsHint) {
            throw new EmbeddingServiceUnavailableException("Remote embedding dimension mismatch: expected "
                    + dimensionsHint + " but received " + embeddingEntries.size());
        }
        int nullValueCount = 0;
        int firstNullIndex = -1;
        for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
            if (embeddingEntries.get(vectorIndex) == null) {
                nullValueCount++;
                if (firstNullIndex < 0) {
                    firstNullIndex = vectorIndex;
                }
            }
        }
        if (nullValueCount > 0) {
            if (nullValueCount == embeddingEntries.size()) {
                throw new EmbeddingServiceUnavailableException("Remote embedding payload invalid: all "
                        + embeddingEntries.size()
                        + " dimensions are null. Likely causes: wrong endpoint (expected /v1/embeddings), "
                        + "non-embedding model, or provider payload bug.");
            }
            throw new EmbeddingServiceUnavailableException("Remote embedding payload invalid: "
                    + nullValueCount
                    + " null values out of "
                    + embeddingEntries.size()
                    + " dimensions (first null index "
                    + firstNullIndex
                    + ").");
        }

        float[] vector = new float[embeddingEntries.size()];
        for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
            vector[vectorIndex] = embeddingEntries.get(vectorIndex);
        }
        return vector;
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

    /**
     * Closes the underlying OpenAI client and releases its resources.
     */
    @Override
    public void close() {
        client.close();
    }

    private static String requireConfiguredApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Remote embedding API key is not configured");
        }
        return apiKey;
    }

    private static String requireConfiguredModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("Remote embedding model is not configured");
        }
        return modelName;
    }

    private static String normalizeSdkBaseUrl(String baseUrl) {
        return OpenAiSdkUrlNormalizer.normalize(baseUrl);
    }

    private static void validateDimensions(int dimensionsHint) {
        if (dimensionsHint <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
    }
}
