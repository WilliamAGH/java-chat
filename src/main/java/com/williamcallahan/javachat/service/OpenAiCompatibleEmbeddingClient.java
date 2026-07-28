package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * OpenAI-compatible embedding client that fails fast on provider errors.
 *
 * <p>Uses the OpenAI Java SDK to call `/embeddings` against the configured base URL and
 * propagates HTTP failures so invalid embeddings are never cached.</p>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int LIVE_EMBEDDING_REQUEST_TIMEOUT_SECONDS = 15;
    private static final int BATCH_EMBEDDING_REQUEST_TIMEOUT_SECONDS = 600;
    private static final int MAX_ERROR_SNIPPET = 512;
    private static final String OPENAI_API_VERSION_SUFFIX = "/v1";
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";
    private static final long NANOSECONDS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final OpenAIClient liveEmbeddingClient;
    private final OpenAIClient batchEmbeddingClient;
    private final String modelName;
    private final int dimensionsHint;
    private final boolean closeBatchEmbeddingClient;
    private final Semaphore liveRequestPermits;
    private final Semaphore batchRequestPermits;
    private final RequestLaunchPacer liveRequestLaunchPacer;
    private final RequestLaunchPacer batchRequestLaunchPacer;
    private final RateLimitDecisionResolver rateLimitDecisionResolver;
    private final Duration liveRequestTimeout;
    private final Duration batchRequestTimeout;
    private final AtomicInteger activeForegroundEmbeddingCount = new AtomicInteger();

    /**
     * Defines the gateway-owned embedding model shape and per-JVM request admission limits.
     *
     * @param modelName embedding model alias exposed by the gateway
     * @param dimensions exact dense-vector dimensions
     * @param liveRequestLimits user-facing request admission limits
     * @param batchRequestLimits ingestion, probe, and warm-up request admission limits
     */
    public record GatewaySettings(
            String modelName, int dimensions, RequestLimits liveRequestLimits, RequestLimits batchRequestLimits) {
        /** Validates the gateway settings before any SDK clients are constructed. */
        public GatewaySettings {
            modelName = requireConfiguredModel(modelName);
            validateDimensions(dimensions);
            liveRequestLimits = Objects.requireNonNull(liveRequestLimits, "liveRequestLimits");
            batchRequestLimits = Objects.requireNonNull(batchRequestLimits, "batchRequestLimits");
        }
    }

    /**
     * Defines one tier's per-JVM concurrency, launch rate, and whole-request deadline.
     *
     * @param maxConcurrentRequests maximum in-flight requests
     * @param requestsPerSecond maximum request launch rate
     * @param totalTimeout whole-operation timeout including local admission and the HTTP call
     */
    public record RequestLimits(int maxConcurrentRequests, double requestsPerSecond, Duration totalTimeout) {
        /** Validates request limits before they become active. */
        public RequestLimits {
            validateRequestConcurrency(maxConcurrentRequests);
            validateRequestRate(requestsPerSecond);
            if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
                throw new IllegalArgumentException("Embedding request timeout must be positive");
            }
        }

        /** Creates user-facing request limits with the production retrieval timeout. */
        public static RequestLimits live(int maxConcurrentRequests, double requestsPerSecond) {
            return new RequestLimits(
                    maxConcurrentRequests,
                    requestsPerSecond,
                    Duration.ofSeconds(LIVE_EMBEDDING_REQUEST_TIMEOUT_SECONDS));
        }

        /** Creates ingestion and probe limits with the batch-capacity timeout. */
        public static RequestLimits batch(int maxConcurrentRequests, double requestsPerSecond) {
            return new RequestLimits(
                    maxConcurrentRequests,
                    requestsPerSecond,
                    Duration.ofSeconds(BATCH_EMBEDDING_REQUEST_TIMEOUT_SECONDS));
        }
    }

    /** Enforces strict tier-local spacing without accumulating burst credit while a request is in flight. */
    private static final class RequestLaunchPacer {
        private final Semaphore pacingPermit = new Semaphore(1, true);
        private final long minimumLaunchIntervalNanos;
        private final EmbeddingProviderCooldown providerCooldown;
        private boolean requestHasLaunched;
        private long previousRequestLaunchNanos;

        private RequestLaunchPacer(double requestsPerSecond, EmbeddingProviderCooldown providerCooldown) {
            validateRequestRate(requestsPerSecond);
            minimumLaunchIntervalNanos = Math.max(1L, (long) Math.ceil(NANOSECONDS_PER_SECOND / requestsPerSecond));
            this.providerCooldown = Objects.requireNonNull(providerCooldown, "providerCooldown");
        }

        private CompletableFuture<CreateEmbeddingResponse> dispatch(
                long requestDeadlineNanos,
                Supplier<CompletableFuture<CreateEmbeddingResponse>> embeddingRequestDispatch)
                throws InterruptedException {
            rejectActiveProviderCooldown();
            if (!pacingPermit.tryAcquire(remainingRequestNanos(requestDeadlineNanos), TimeUnit.NANOSECONDS)) {
                throw gatewayDeadlineFailure("Gateway embedding request pacing exceeded the request deadline");
            }
            try {
                rejectActiveProviderCooldown();
                waitForMinimumLaunchInterval(requestDeadlineNanos);
                CompletableFuture<CreateEmbeddingResponse> embeddingResponseFuture =
                        providerCooldown.dispatch(embeddingRequestDispatch);
                previousRequestLaunchNanos = System.nanoTime();
                requestHasLaunched = true;
                return embeddingResponseFuture;
            } finally {
                pacingPermit.release();
            }
        }

        private void waitForMinimumLaunchInterval(long requestDeadlineNanos) throws InterruptedException {
            while (true) {
                long pacingDeadlineNanos =
                        requestHasLaunched ? saturatedAdd(previousRequestLaunchNanos, minimumLaunchIntervalNanos) : 0;
                long remainingLaunchDelayNanos = pacingDeadlineNanos - System.nanoTime();
                if (remainingLaunchDelayNanos <= 0) {
                    return;
                }
                long remainingDeadlineNanos = remainingRequestNanos(requestDeadlineNanos);
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingLaunchDelayNanos, remainingDeadlineNanos));
            }
        }

        private void rejectActiveProviderCooldown() {
            providerCooldown.rejectActive();
        }

        private void recordProviderCooldown(long retryAfterSeconds) {
            providerCooldown.record(retryAfterSeconds);
        }
    }

    /** Owns the Retry-After window for one tier, so a batch 429 never stalls live requests. */
    private static final class EmbeddingProviderCooldown {
        private long providerCooldownDeadlineNanos;

        private synchronized CompletableFuture<CreateEmbeddingResponse> dispatch(
                Supplier<CompletableFuture<CreateEmbeddingResponse>> embeddingRequestDispatch) {
            rejectActive();
            return embeddingRequestDispatch.get();
        }

        private synchronized void rejectActive() {
            long remainingCooldownNanos = providerCooldownDeadlineNanos - System.nanoTime();
            if (remainingCooldownNanos > 0) {
                long retryAfterSeconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remainingCooldownNanos));
                throw new EmbeddingServiceTemporarilyUnavailableException(
                        "Remote embedding provider is rate limited; retry after " + retryAfterSeconds + " seconds");
            }
        }

        private synchronized void record(long retryAfterSeconds) {
            if (retryAfterSeconds <= 0) {
                return;
            }
            long cooldownDurationNanos = TimeUnit.SECONDS.toNanos(retryAfterSeconds);
            long cooldownDeadlineNanos = saturatedAdd(System.nanoTime(), cooldownDurationNanos);
            providerCooldownDeadlineNanos = Math.max(providerCooldownDeadlineNanos, cooldownDeadlineNanos);
        }
    }

    /**
     * Creates an OpenAI-compatible embedding client backed by a remote REST API endpoint.
     *
     * @param baseUrl base URL for the embedding API
     * @param apiKey API key for the embedding provider
     * @param gatewaySettings embedding model shape and per-tier request admission limits
     * @return embedding client configured for the remote endpoint
     */
    public static OpenAiCompatibleEmbeddingClient create(
            String baseUrl, String apiKey, GatewaySettings gatewaySettings) {
        GatewaySettings requiredGatewaySettings = Objects.requireNonNull(gatewaySettings, "gatewaySettings");
        String configuredApiKey = requireConfiguredApiKey(apiKey);
        String configuredBaseUrl = requireVersionedBaseUrl(baseUrl);
        OpenAIClient liveEmbeddingClient = createTieredClient(configuredApiKey, configuredBaseUrl, LlmGatewayTier.LIVE);
        OpenAIClient batchEmbeddingClient =
                createTieredClient(configuredApiKey, configuredBaseUrl, LlmGatewayTier.BATCH);
        return new OpenAiCompatibleEmbeddingClient(liveEmbeddingClient, batchEmbeddingClient, requiredGatewaySettings);
    }

    static OpenAiCompatibleEmbeddingClient create(OpenAIClient client, GatewaySettings gatewaySettings) {
        OpenAIClient embeddingClient = Objects.requireNonNull(client, "client");
        return new OpenAiCompatibleEmbeddingClient(
                embeddingClient, embeddingClient, Objects.requireNonNull(gatewaySettings, "gatewaySettings"), false);
    }

    OpenAiCompatibleEmbeddingClient(
            OpenAIClient liveEmbeddingClient, OpenAIClient batchEmbeddingClient, GatewaySettings gatewaySettings) {
        this(liveEmbeddingClient, batchEmbeddingClient, gatewaySettings, true);
    }

    private OpenAiCompatibleEmbeddingClient(
            OpenAIClient liveEmbeddingClient,
            OpenAIClient batchEmbeddingClient,
            GatewaySettings gatewaySettings,
            boolean closeBatchEmbeddingClient) {
        this.liveEmbeddingClient = Objects.requireNonNull(liveEmbeddingClient, "liveEmbeddingClient");
        this.batchEmbeddingClient = Objects.requireNonNull(batchEmbeddingClient, "batchEmbeddingClient");
        this.rateLimitDecisionResolver = new RateLimitDecisionResolver(new RateLimitHeaderParser());
        GatewaySettings requiredGatewaySettings = Objects.requireNonNull(gatewaySettings, "gatewaySettings");
        this.modelName = requiredGatewaySettings.modelName();
        this.dimensionsHint = requiredGatewaySettings.dimensions();
        this.closeBatchEmbeddingClient = closeBatchEmbeddingClient;
        RequestLimits liveLimits = requiredGatewaySettings.liveRequestLimits();
        RequestLimits batchLimits = requiredGatewaySettings.batchRequestLimits();
        this.liveRequestPermits = new Semaphore(liveLimits.maxConcurrentRequests(), true);
        this.batchRequestPermits = new Semaphore(batchLimits.maxConcurrentRequests(), true);
        this.liveRequestLaunchPacer =
                new RequestLaunchPacer(liveLimits.requestsPerSecond(), new EmbeddingProviderCooldown());
        this.batchRequestLaunchPacer =
                new RequestLaunchPacer(batchLimits.requestsPerSecond(), new EmbeddingProviderCooldown());
        this.liveRequestTimeout = liveLimits.totalTimeout();
        this.batchRequestTimeout = batchLimits.totalTimeout();
        log.info(
                "[EMBEDDING] Gateway request limits configured (liveConcurrency={}, liveRequestsPerSecond={}, batchConcurrency={}, batchRequestsPerSecond={})",
                liveLimits.maxConcurrentRequests(),
                liveLimits.requestsPerSecond(),
                batchLimits.maxConcurrentRequests(),
                batchLimits.requestsPerSecond());
    }

    @Override
    public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
        Objects.requireNonNull(requestTier, "requestTier");
        return embed(texts, requestTier, requestTimeoutFor(requestTier));
    }

    /**
     * Bounds the whole embedding operation by the tighter of the caller budget and the tier ceiling.
     *
     * <p>The caller's remaining stage budget can only tighten the configured tier timeout, never
     * relax it, so a nearly exhausted retrieval stage fails this hop fast while an unhurried
     * caller observes exactly the configured timeout.</p>
     */
    @Override
    public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier, Duration requestTimeout) {
        Objects.requireNonNull(requestTier, "requestTier");
        Duration requiredRequestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        activeForegroundEmbeddingCount.incrementAndGet();
        try {
            return createEmbeddings(texts, requestTier, tighterRequestTimeout(requestTier, requiredRequestTimeout));
        } finally {
            activeForegroundEmbeddingCount.decrementAndGet();
        }
    }

    /**
     * Issues a probe only when no foreground embedding was active at admission time.
     *
     * <p>The OpenAI Java SDK's async future does not guarantee cancellation of its underlying HTTP
     * call. Therefore a foreground request that arrives after this check cannot preempt an already
     * admitted probe. Abandoned calls retain their concurrency permit until SDK completion, and each
     * admitted embedding request performs one SDK attempt without mixing live and batch limits.</p>
     *
     * @throws EmbeddingProbeDeferredException when foreground embedding work is already active
     */
    @Override
    public void warmUp() {
        if (activeForegroundEmbeddingCount.get() > 0) {
            throw new EmbeddingProbeDeferredException();
        }
        createEmbeddings(List.of(EMBEDDING_WARM_UP_PROBE_TEXT), LlmGatewayTier.BATCH, batchRequestTimeout);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private List<float[]> createEmbeddings(List<String> texts, LlmGatewayTier requestTier, Duration requestTimeout) {
        EmbeddingCreateParams embeddingRequest = EmbeddingCreateParams.builder()
                .model(modelName)
                .inputOfArrayOfStrings(texts)
                .build();
        Semaphore requestPermits = requestPermitsFor(requestTier);
        long requestDeadlineNanos = System.nanoTime() + requestTimeout.toNanos();
        boolean requestPermitAcquired = false;
        try {
            if (!requestPermits.tryAcquire(remainingRequestNanos(requestDeadlineNanos), TimeUnit.NANOSECONDS)) {
                throw gatewayDeadlineFailure(
                        "Gateway embedding request concurrency limit exceeded the request deadline");
            }
            requestPermitAcquired = true;
            OpenAIClientAsync requestClient = clientFor(requestTier).async();
            CompletableFuture<CreateEmbeddingResponse> embeddingResponseFuture = requestLaunchPacerFor(requestTier)
                    .dispatch(requestDeadlineNanos, () -> requestClient
                            .embeddings()
                            .create(
                                    embeddingRequest,
                                    RequestOptions.builder()
                                            .timeout(embeddingTimeout(remainingRequestDuration(requestDeadlineNanos)))
                                            .build()));
            CompletableFuture<CreateEmbeddingResponse> accountedEmbeddingResponseFuture =
                    embeddingResponseFuture.whenComplete((completedEmbeddingResponse, embeddingFailure) -> {
                        recordProviderCooldownFromFutureFailure(embeddingFailure, requestTier);
                        requestPermits.release();
                    });
            requestPermitAcquired = false;
            return execute(accountedEmbeddingResponseFuture, texts.size(), requestDeadlineNanos);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new EmbeddingServiceTemporarilyUnavailableException(
                    "Interrupted during gateway embedding request admission or completion", interruptedException);
        } catch (OpenAIServiceException exception) {
            recordProviderCooldown(exception, requestTier);
            throw wrapServiceError(exception);
        } catch (OpenAIRetryableException exception) {
            throw wrapRetryableError(exception);
        } catch (EmbeddingServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw wrapFatalError(exception);
        } finally {
            if (requestPermitAcquired) {
                requestPermits.release();
            }
        }
    }

    private List<float[]> execute(
            CompletableFuture<CreateEmbeddingResponse> embeddingResponseFuture,
            int expectedCount,
            long requestDeadlineNanos)
            throws InterruptedException {
        try {
            CreateEmbeddingResponse embeddingResponse =
                    embeddingResponseFuture.get(remainingRequestNanos(requestDeadlineNanos), TimeUnit.NANOSECONDS);
            return parseResponse(embeddingResponse, expectedCount);
        } catch (InterruptedException interruptedException) {
            throw interruptedException;
        } catch (TimeoutException timeoutException) {
            throw new EmbeddingServiceTemporarilyUnavailableException(
                    "Gateway embedding request exceeded the whole-operation deadline", timeoutException);
        } catch (ExecutionException executionException) {
            throw wrapAsyncFailure(executionException.getCause());
        }
    }

    private EmbeddingServiceUnavailableException wrapAsyncFailure(Throwable asyncFailure) {
        Throwable providerFailure = asyncFailure;
        while (providerFailure instanceof CompletionException && providerFailure.getCause() != null) {
            providerFailure = providerFailure.getCause();
        }
        if (providerFailure instanceof OpenAIServiceException serviceException) {
            return wrapServiceError(serviceException);
        }
        if (providerFailure instanceof OpenAIRetryableException retryableException) {
            return wrapRetryableError(retryableException);
        }
        if (providerFailure instanceof EmbeddingServiceUnavailableException embeddingException) {
            return embeddingException;
        }
        if (providerFailure instanceof RuntimeException runtimeException) {
            return wrapFatalError(runtimeException);
        }
        return new EmbeddingServiceTemporarilyUnavailableException("Remote embedding request failed", providerFailure);
    }

    private EmbeddingServiceUnavailableException wrapServiceError(OpenAIServiceException exception) {
        int statusCode = exception.statusCode();
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            try {
                rateLimitDecisionResolver.resolveFromOpenAiRetryAfterHeaders(exception.headers());
            } catch (RateLimitDecisionException rateLimitDecisionFailure) {
                rateLimitDecisionFailure.addSuppressed(exception);
                return new EmbeddingServiceTemporarilyUnavailableException(
                        "Remote embedding provider returned HTTP 429 without authoritative retry timing",
                        rateLimitDecisionFailure);
            }
        }
        String details = sanitizeMessage(exception.getMessage());
        String failureMessage = details.isBlank()
                ? "Remote embedding provider returned HTTP " + statusCode
                : "Remote embedding provider returned HTTP " + statusCode + ": " + details;
        if (statusCode == HttpStatus.REQUEST_TIMEOUT.value()
                || statusCode == HttpStatus.CONFLICT.value()
                || statusCode == HttpStatus.TOO_MANY_REQUESTS.value()
                || statusCode >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return new EmbeddingServiceTemporarilyUnavailableException(failureMessage, exception);
        }
        return new EmbeddingServiceUnavailableException(failureMessage, exception);
    }

    private void recordProviderCooldownFromFutureFailure(Throwable embeddingFailure, LlmGatewayTier requestTier) {
        Throwable providerFailure = embeddingFailure;
        while (providerFailure instanceof CompletionException && providerFailure.getCause() != null) {
            providerFailure = providerFailure.getCause();
        }
        if (providerFailure instanceof OpenAIServiceException serviceException) {
            recordProviderCooldown(serviceException, requestTier);
        }
    }

    private void recordProviderCooldown(OpenAIServiceException serviceException, LlmGatewayTier requestTier) {
        if (serviceException.statusCode() != HttpStatus.TOO_MANY_REQUESTS.value()) {
            return;
        }
        try {
            RateLimitDecision rateLimitDecision =
                    rateLimitDecisionResolver.resolveFromOpenAiRetryAfterHeaders(serviceException.headers());
            requestLaunchPacerFor(requestTier).recordProviderCooldown(rateLimitDecision.retryAfterSeconds());
        } catch (RateLimitDecisionException rateLimitDecisionFailure) {
            log.warn(
                    "[EMBEDDING] HTTP 429 omitted authoritative retry timing (tier={})",
                    requestTier.requestHeader(),
                    rateLimitDecisionFailure);
        }
    }

    private EmbeddingServiceUnavailableException wrapRetryableError(OpenAIRetryableException exception) {
        String details = sanitizeMessage(exception.getMessage());
        String failureMessage =
                details.isBlank() ? "Remote embedding request failed" : "Remote embedding request failed: " + details;
        return new EmbeddingServiceTemporarilyUnavailableException(failureMessage, exception);
    }

    private EmbeddingServiceUnavailableException wrapFatalError(RuntimeException exception) {
        Optional<TimeoutException> providerTimeout = providerTimeout(exception);
        if (providerTimeout.isPresent()) {
            return new EmbeddingServiceTemporarilyUnavailableException(
                    "Remote embedding request exceeded its transport timeout", providerTimeout.get());
        }
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

    private static Optional<TimeoutException> providerTimeout(RuntimeException providerFailure) {
        Set<Throwable> inspectedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failureInChain = providerFailure;
        while (failureInChain != null && inspectedFailures.add(failureInChain)) {
            if (failureInChain instanceof SocketTimeoutException
                    || (failureInChain.getClass().equals(InterruptedIOException.class)
                            && OK_HTTP_CALL_TIMEOUT_MESSAGE.equals(failureInChain.getMessage()))) {
                TimeoutException timeoutFailure = new TimeoutException("Remote embedding transport deadline exceeded");
                timeoutFailure.initCause(providerFailure);
                return Optional.of(timeoutFailure);
            }
            failureInChain = failureInChain.getCause();
        }
        return Optional.empty();
    }

    private Timeout embeddingTimeout(Duration transportBudget) {
        Duration connectTimeout = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS).compareTo(transportBudget) <= 0
                ? Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)
                : transportBudget;
        return Timeout.builder()
                .connect(connectTimeout)
                .request(transportBudget)
                .read(transportBudget)
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
        RuntimeException closeFailure = null;
        try {
            liveEmbeddingClient.close();
        } catch (RuntimeException liveCloseFailure) {
            closeFailure = liveCloseFailure;
        }
        if (closeBatchEmbeddingClient) {
            try {
                batchEmbeddingClient.close();
            } catch (RuntimeException batchCloseFailure) {
                if (closeFailure == null) {
                    closeFailure = batchCloseFailure;
                } else {
                    closeFailure.addSuppressed(batchCloseFailure);
                }
            }
        }
        if (closeFailure != null) {
            throw new EmbeddingClientCloseException(closeFailure);
        }
    }

    /**
     * Reports a failed embedding-client shutdown after every owned client was given a close attempt.
     */
    public static final class EmbeddingClientCloseException extends RuntimeException {
        private EmbeddingClientCloseException(RuntimeException primaryCloseFailure) {
            super("Failed to close an embedding client", primaryCloseFailure);
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

    private static void validateRequestConcurrency(int maxConcurrentRequests) {
        if (maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException(
                    "Embedding request concurrency must be positive, got: " + maxConcurrentRequests);
        }
    }

    private static void validateRequestRate(double requestsPerSecond) {
        if (!Double.isFinite(requestsPerSecond) || requestsPerSecond <= 0.0) {
            throw new IllegalArgumentException(
                    "Embedding request rate must be finite and positive, got: " + requestsPerSecond);
        }
    }

    private static long remainingRequestNanos(long requestDeadlineNanos) {
        long remainingNanos = requestDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw gatewayDeadlineFailure("Gateway embedding request deadline elapsed locally");
        }
        return remainingNanos;
    }

    private static EmbeddingServiceTemporarilyUnavailableException gatewayDeadlineFailure(String failureMessage) {
        return new EmbeddingServiceTemporarilyUnavailableException(
                failureMessage, new TimeoutException(failureMessage));
    }

    private static long saturatedAdd(long leftOperand, long rightOperand) {
        long sum = leftOperand + rightOperand;
        if (((leftOperand ^ sum) & (rightOperand ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private static Duration remainingRequestDuration(long requestDeadlineNanos) {
        return Duration.ofNanos(remainingRequestNanos(requestDeadlineNanos));
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
                .putHeader(LlmGatewayTier.REQUEST_TIER_HEADER, requestTier.requestHeader())
                .maxRetries(0);
        return clientBuilder.build();
    }

    private OpenAIClient clientFor(LlmGatewayTier requestTier) {
        return switch (requestTier) {
            case LIVE -> liveEmbeddingClient;
            case BATCH -> batchEmbeddingClient;
        };
    }

    private Semaphore requestPermitsFor(LlmGatewayTier requestTier) {
        return switch (requestTier) {
            case LIVE -> liveRequestPermits;
            case BATCH -> batchRequestPermits;
        };
    }

    private RequestLaunchPacer requestLaunchPacerFor(LlmGatewayTier requestTier) {
        return switch (requestTier) {
            case LIVE -> liveRequestLaunchPacer;
            case BATCH -> batchRequestLaunchPacer;
        };
    }

    private Duration requestTimeoutFor(LlmGatewayTier requestTier) {
        return switch (requestTier) {
            case LIVE -> liveRequestTimeout;
            case BATCH -> batchRequestTimeout;
        };
    }

    private Duration tighterRequestTimeout(LlmGatewayTier requestTier, Duration requestTimeout) {
        Duration configuredTierTimeout = requestTimeoutFor(requestTier);
        return requestTimeout.compareTo(configuredTierTimeout) < 0 ? requestTimeout : configuredTierTimeout;
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
