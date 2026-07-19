package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int LIVE_EMBEDDING_REQUEST_TIMEOUT_SECONDS = 60;
    private static final int BATCH_EMBEDDING_REQUEST_TIMEOUT_SECONDS = 600;
    private static final int MAX_ERROR_SNIPPET = 512;
    private static final String OPENAI_API_VERSION_SUFFIX = "/v1";

    private final OpenAIClient liveEmbeddingClient;
    private final OpenAIClient batchEmbeddingClient;
    private final String modelName;
    private final int dimensionsHint;
    private final boolean closeBatchEmbeddingClient;
    private final AtomicInteger activeForegroundEmbeddingCount = new AtomicInteger();

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
        String configuredApiKey = requireConfiguredApiKey(apiKey);
        String configuredBaseUrl = requireVersionedBaseUrl(baseUrl);
        OpenAIClient liveEmbeddingClient = createTieredClient(configuredApiKey, configuredBaseUrl, LlmGatewayTier.LIVE);
        OpenAIClient batchEmbeddingClient =
                createTieredClient(configuredApiKey, configuredBaseUrl, LlmGatewayTier.BATCH);
        return new OpenAiCompatibleEmbeddingClient(
                liveEmbeddingClient, batchEmbeddingClient, requireConfiguredModel(modelName), dimensionsHint);
    }

    static OpenAiCompatibleEmbeddingClient create(OpenAIClient client, String modelName, int dimensionsHint) {
        validateDimensions(dimensionsHint);
        OpenAIClient embeddingClient = Objects.requireNonNull(client, "client");
        return new OpenAiCompatibleEmbeddingClient(
                embeddingClient, embeddingClient, requireConfiguredModel(modelName), dimensionsHint, false);
    }

    OpenAiCompatibleEmbeddingClient(
            OpenAIClient liveEmbeddingClient, OpenAIClient batchEmbeddingClient, String modelName, int dimensionsHint) {
        this(liveEmbeddingClient, batchEmbeddingClient, modelName, dimensionsHint, true);
    }

    private OpenAiCompatibleEmbeddingClient(
            OpenAIClient liveEmbeddingClient,
            OpenAIClient batchEmbeddingClient,
            String modelName,
            int dimensionsHint,
            boolean closeBatchEmbeddingClient) {
        this.liveEmbeddingClient = Objects.requireNonNull(liveEmbeddingClient, "liveEmbeddingClient");
        this.batchEmbeddingClient = Objects.requireNonNull(batchEmbeddingClient, "batchEmbeddingClient");
        this.modelName = modelName;
        this.dimensionsHint = dimensionsHint;
        this.closeBatchEmbeddingClient = closeBatchEmbeddingClient;
    }

    @Override
    public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
        Objects.requireNonNull(requestTier, "requestTier");
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        activeForegroundEmbeddingCount.incrementAndGet();
        try {
            return createEmbeddings(texts, requestTier);
        } finally {
            activeForegroundEmbeddingCount.decrementAndGet();
        }
    }

    /**
     * Issues a probe only when no foreground embedding was active at admission time.
     *
     * <p>The OpenAI Java SDK's blocking embedding API does not expose a cancellation handle.
     * Therefore a foreground request that arrives after this check cannot preempt an already
     * admitted probe. Batch clients perform one attempt, which bounds that unavoidable race
     * without delaying a foreground request behind an application-level lock.</p>
     *
     * @throws EmbeddingProbeDeferredException when foreground embedding work is already active
     */
    @Override
    public void warmUp() {
        if (activeForegroundEmbeddingCount.get() > 0) {
            throw new EmbeddingProbeDeferredException();
        }
        createEmbeddings(List.of(EMBEDDING_WARM_UP_PROBE_TEXT), LlmGatewayTier.BATCH);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private List<float[]> createEmbeddings(List<String> texts, LlmGatewayTier requestTier) {
        EmbeddingCreateParams embeddingRequest = EmbeddingCreateParams.builder()
                .model(modelName)
                .inputOfArrayOfStrings(texts)
                .build();
        RequestOptions requestOptions =
                RequestOptions.builder().timeout(embeddingTimeout(requestTier)).build();
        return execute(clientFor(requestTier), embeddingRequest, requestOptions, texts.size());
    }

    private List<float[]> execute(
            OpenAIClient requestClient,
            EmbeddingCreateParams embeddingRequest,
            RequestOptions requestOptions,
            int expectedCount) {
        try {
            CreateEmbeddingResponse embeddingResponse =
                    requestClient.embeddings().create(embeddingRequest, requestOptions);
            return parseResponse(embeddingResponse, expectedCount);
        } catch (OpenAIServiceException exception) {
            throw wrapServiceError(exception);
        } catch (OpenAIRetryableException exception) {
            throw wrapRetryableError(exception);
        } catch (EmbeddingServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw wrapFatalError(exception);
        }
    }

    private EmbeddingServiceUnavailableException wrapServiceError(OpenAIServiceException exception) {
        int statusCode = exception.statusCode();
        String details = sanitizeMessage(exception.getMessage());
        String failureMessage = details.isBlank()
                ? "Remote embedding provider returned HTTP " + statusCode
                : "Remote embedding provider returned HTTP " + statusCode + ": " + details;
        return new EmbeddingServiceUnavailableException(failureMessage, exception);
    }

    private EmbeddingServiceUnavailableException wrapRetryableError(OpenAIRetryableException exception) {
        String details = sanitizeMessage(exception.getMessage());
        String failureMessage =
                details.isBlank() ? "Remote embedding request failed" : "Remote embedding request failed: " + details;
        return new EmbeddingServiceUnavailableException(failureMessage, exception);
    }

    private EmbeddingServiceUnavailableException wrapFatalError(RuntimeException exception) {
        String details = sanitizeMessage(exception.getMessage());
        log.warn(
                "[EMBEDDING] Remote embedding call failed (exception type: {}, details: {})",
                exception.getClass().getSimpleName(),
                details.isBlank() ? "none" : details,
                exception);
        String failureMessage =
                details.isBlank() ? "Remote embedding call failed" : "Remote embedding call failed: " + details;
        return new EmbeddingServiceUnavailableException(failureMessage, exception);
    }

    private Timeout embeddingTimeout(LlmGatewayTier requestTier) {
        Duration connectTimeout = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS);
        Duration requestTimeout =
                switch (requestTier) {
                    case LIVE -> Duration.ofSeconds(LIVE_EMBEDDING_REQUEST_TIMEOUT_SECONDS);
                    case BATCH -> Duration.ofSeconds(BATCH_EMBEDDING_REQUEST_TIMEOUT_SECONDS);
                };
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
        List<com.openai.models.embeddings.Embedding> embeddingEntries = response.data();
        if (embeddingEntries.size() != expectedCount) {
            throw new EmbeddingServiceUnavailableException("Remote embedding response count mismatch: expected "
                    + expectedCount + " but received " + embeddingEntries.size());
        }

        float[][] embeddingsByIndex = new float[expectedCount][];
        boolean[] populatedEmbeddingIndexes = new boolean[expectedCount];

        for (int itemIndex = 0; itemIndex < embeddingEntries.size(); itemIndex++) {
            com.openai.models.embeddings.Embedding embeddingEntry = embeddingEntries.get(itemIndex);
            if (embeddingEntry == null) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response contained null entry at index " + itemIndex);
            }
            int targetIndex = requiredEmbeddingIndex(itemIndex, embeddingEntry, expectedCount);
            if (populatedEmbeddingIndexes[targetIndex]) {
                throw new EmbeddingServiceUnavailableException(
                        "Remote embedding response duplicated index " + targetIndex);
            }
            embeddingsByIndex[targetIndex] = toFloatVector(embeddingEntry.embedding());
            populatedEmbeddingIndexes[targetIndex] = true;
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

    private int requiredEmbeddingIndex(
            int responsePosition, com.openai.models.embeddings.Embedding embedding, int expectedCount) {
        long responseIndex = embedding
                ._index()
                .asNumber()
                .map(Number::longValue)
                .orElseThrow(() -> new EmbeddingServiceUnavailableException(
                        "Remote embedding response omitted index at position " + responsePosition));
        if (responseIndex < 0 || responseIndex > Integer.MAX_VALUE) {
            throw new EmbeddingServiceUnavailableException(
                    "Remote embedding response contained out-of-range index " + responseIndex);
        }
        int index = (int) responseIndex;
        if (index >= expectedCount) {
            throw new EmbeddingServiceUnavailableException(
                    "Remote embedding response index " + index + " exceeded expected response count " + expectedCount);
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
        liveEmbeddingClient.close();
        if (closeBatchEmbeddingClient) {
            batchEmbeddingClient.close();
        }
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

    private static String requireVersionedBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("OpenAI gateway base URL is not configured");
        }
        String configuredBaseUrl = baseUrl.trim();
        if (!configuredBaseUrl.endsWith(OPENAI_API_VERSION_SUFFIX)) {
            throw new IllegalStateException("OpenAI gateway base URL must end with " + OPENAI_API_VERSION_SUFFIX);
        }
        return configuredBaseUrl;
    }

    private static OpenAIClient createTieredClient(String apiKey, String baseUrl, LlmGatewayTier requestTier) {
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .putHeader(LlmGatewayTier.REQUEST_TIER_HEADER, requestTier.requestHeader());
        if (requestTier == LlmGatewayTier.BATCH) {
            clientBuilder.maxRetries(0);
        }
        return clientBuilder.build();
    }

    private OpenAIClient clientFor(LlmGatewayTier requestTier) {
        return switch (requestTier) {
            case LIVE -> liveEmbeddingClient;
            case BATCH -> batchEmbeddingClient;
        };
    }

    private static void validateDimensions(int dimensionsHint) {
        if (dimensionsHint <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
    }

    /** Signals that a background probe yielded admission to active foreground embedding work. */
    static final class EmbeddingProbeDeferredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EmbeddingProbeDeferredException() {
            super("Embedding probe deferred while foreground embedding work is active");
        }
    }
}
