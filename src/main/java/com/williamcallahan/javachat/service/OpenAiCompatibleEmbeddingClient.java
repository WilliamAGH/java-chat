package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        try {
            EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                    .model(modelName)
                    .inputOfArrayOfStrings(texts)
                    .build();
            RequestOptions requestOptions =
                    RequestOptions.builder().timeout(embeddingTimeout()).build();
            CreateEmbeddingResponse response = client.embeddings().create(params, requestOptions);
            return parseResponse(response, texts.size());
        } catch (OpenAIServiceException exception) {
            String details = sanitizeMessage(exception.getMessage());
            String failureMessage = details.isBlank()
                    ? "Remote embedding provider returned HTTP " + exception.statusCode()
                    : "Remote embedding provider returned HTTP " + exception.statusCode() + ": " + details;
            throw new EmbeddingServiceUnavailableException(failureMessage, exception);
        } catch (RuntimeException exception) {
            log.warn(
                    "[EMBEDDING] Remote embedding call failed (exception type: {})",
                    exception.getClass().getSimpleName());
            throw new EmbeddingServiceUnavailableException("Remote embedding call failed", exception);
        }
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

    private List<float[]> parseResponse(CreateEmbeddingResponse response, int expectedCount) {
        if (response == null) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response was null");
        }
        List<com.openai.models.embeddings.Embedding> data = response.data();
        if (data.isEmpty()) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response missing embedding entries");
        }

        List<float[]> embeddingsByIndex = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            embeddingsByIndex.add(null);
        }

        for (int itemIndex = 0; itemIndex < data.size(); itemIndex++) {
            com.openai.models.embeddings.Embedding item = data.get(itemIndex);
            if (item == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response contained null entry at index " + itemIndex);
            }
            int targetIndex = safeEmbeddingIndex(itemIndex, item, expectedCount);
            if (targetIndex < 0 || targetIndex >= expectedCount) {
                continue;
            }
            float[] vector = toFloatVector(item.embedding());
            embeddingsByIndex.set(targetIndex, vector);
        }

        List<float[]> orderedEmbeddings = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            float[] embedding = embeddingsByIndex.get(index);
            if (embedding == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response missing embedding for index " + index);
            }
            orderedEmbeddings.add(embedding);
        }

        return List.copyOf(orderedEmbeddings);
    }

    private int safeEmbeddingIndex(int fallbackIndex, com.openai.models.embeddings.Embedding item, int expectedCount) {
        long responseIndex = item._index().asNumber().map(Number::longValue).orElse((long) fallbackIndex);
        if (responseIndex < 0L || responseIndex > (long) Integer.MAX_VALUE) {
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
        float[] vector = new float[embeddingEntries.size()];
        for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
            Float entry = embeddingEntries.get(vectorIndex);
            if (entry == null) {
                throw new EmbeddingServiceUnavailableException("Null embedding value at index " + vectorIndex);
            }
            vector[vectorIndex] = entry;
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
