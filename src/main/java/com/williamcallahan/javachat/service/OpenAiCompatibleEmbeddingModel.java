package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Simple OpenAI-compatible EmbeddingModel.
 * Uses the OpenAI Java SDK to call `/embeddings` against the configured base URL.
 */
public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private final OpenAIClient client;
    private final String modelName;
    private final int dimensionsHint;

    /**
     * Wraps remote embedding API failures as a runtime exception with concise context.
     */
    private static final class EmbeddingApiResponseException extends IllegalStateException {
        private EmbeddingApiResponseException(String message, Exception cause) {
            super(message, cause);
        }

        private EmbeddingApiResponseException(String message) {
            super(message);
        }
    }

    /**
     * Creates an OpenAI-compatible embedding model backed by a remote REST API endpoint.
     *
     * @param baseUrl base URL for the embedding API
     * @param apiKey API key for the embedding provider
     * @param modelName model identifier for embeddings
     * @param dimensionsHint expected embedding dimensions (used as a hint)
     * @return embedding model configured for the remote endpoint
     */
    public static OpenAiCompatibleEmbeddingModel create(
            String baseUrl, String apiKey, String modelName, int dimensionsHint) {
        validateDimensions(dimensionsHint);
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(requireConfiguredApiKey(apiKey))
                .baseUrl(normalizeSdkBaseUrl(baseUrl))
                .build();
        return new OpenAiCompatibleEmbeddingModel(client, requireConfiguredModel(modelName), dimensionsHint);
    }

    static OpenAiCompatibleEmbeddingModel create(OpenAIClient client, String modelName, int dimensionsHint) {
        validateDimensions(dimensionsHint);
        return new OpenAiCompatibleEmbeddingModel(
                Objects.requireNonNull(client, "client"), requireConfiguredModel(modelName), dimensionsHint);
    }

    private OpenAiCompatibleEmbeddingModel(OpenAIClient client, String modelName, int dimensionsHint) {
        this.client = client;
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint;
    }

    /**
     * Calls the OpenAI-compatible embeddings endpoint for all inputs in the request.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        try {
            EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                    .model(modelName)
                    .inputOfArrayOfStrings(instructions)
                    .build();
            RequestOptions requestOptions =
                    RequestOptions.builder().timeout(embeddingTimeout()).build();
            CreateEmbeddingResponse response = client.embeddings().create(params, requestOptions);
            List<Embedding> embeddings = parseResponse(response, instructions.size());
            return new EmbeddingResponse(embeddings);

        } catch (EmbeddingApiResponseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "[EMBEDDING] Remote embedding call failed (exception type: {})",
                    exception.getClass().getSimpleName());
            throw new EmbeddingApiResponseException("Remote embedding call failed", exception);
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

    private List<Embedding> parseResponse(CreateEmbeddingResponse response, int expectedCount) {
        if (response == null) {
            throw new EmbeddingApiResponseException("Remote embedding response was null");
        }
        List<com.openai.models.embeddings.Embedding> data = response.data();
        if (data.isEmpty()) {
            throw new EmbeddingApiResponseException("Remote embedding response missing embedding entries");
        }

        List<Embedding> embeddingsByIndex = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            embeddingsByIndex.add(null);
        }

        for (int itemIndex = 0; itemIndex < data.size(); itemIndex++) {
            com.openai.models.embeddings.Embedding item = data.get(itemIndex);
            if (item == null) {
                throw new EmbeddingApiResponseException(
                        "Remote embedding response contained null entry at index " + itemIndex);
            }
            int targetIndex = safeEmbeddingIndex(itemIndex, item, expectedCount);
            if (targetIndex < 0 || targetIndex >= expectedCount) {
                continue;
            }
            float[] vector = toFloatVector(item.embedding());
            embeddingsByIndex.set(targetIndex, new Embedding(vector, targetIndex));
        }

        List<Embedding> orderedEmbeddings = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            Embedding embedding = embeddingsByIndex.get(index);
            if (embedding == null) {
                throw new EmbeddingApiResponseException(
                        "Remote embedding response missing embedding for index " + index);
            }
            orderedEmbeddings.add(embedding);
        }

        return orderedEmbeddings;
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

    /**
     * Embeds a single document by delegating to the remote embeddings endpoint.
     */
    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        EmbeddingRequest embeddingRequest = new EmbeddingRequest(List.of(document.getText()), null);
        EmbeddingResponse embeddingResponse = call(embeddingRequest);
        if (embeddingResponse.getResults().isEmpty()) {
            throw new EmbeddingApiResponseException("Embedding response was empty");
        }
        return embeddingResponse.getResults().get(0).getOutput();
    }

    private float[] toFloatVector(List<Float> embeddingEntries) {
        if (embeddingEntries == null || embeddingEntries.isEmpty()) {
            throw new EmbeddingApiResponseException("Remote embedding response missing embedding values");
        }
        float[] vector = new float[embeddingEntries.size()];
        for (int vectorIndex = 0; vectorIndex < embeddingEntries.size(); vectorIndex++) {
            Float entry = embeddingEntries.get(vectorIndex);
            if (entry == null) {
                throw new EmbeddingApiResponseException("Null embedding value at index " + vectorIndex);
            }
            vector[vectorIndex] = entry;
        }
        return vector;
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
