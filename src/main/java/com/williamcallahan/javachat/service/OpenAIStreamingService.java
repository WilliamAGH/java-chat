package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI Java SDK-based streaming service that provides clean, reliable streaming
 * without manual SSE parsing, token buffering artifacts, or spacing issues.
 *
 * This service replaces the complex manual SSE handling in ResilientApiClient
 * with the OpenAI Java SDK's native streaming support.
 */
@Service
public class OpenAIStreamingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingService.class);

    private static final int MAX_COMPLETION_TOKENS = 4000;
    private static final int COMPLETE_REQUEST_TIMEOUT_SECONDS = 30;

    /** Prefix matching gpt-5, gpt-5.2, gpt-5.2-pro, etc. */
    private static final String GPT_5_MODEL_PREFIX = "gpt-5";

    /** Safe token budget for GPT-5.2 input (8K limit). */
    private static final int MAX_TOKENS_GPT5_INPUT = 7000;

    /** Generous token budget for high-context models. */
    private static final int MAX_TOKENS_DEFAULT_INPUT = 100_000;

    /** Truncation notice for GPT-5 family models with 8K input limit. */
    private static final String TRUNCATION_NOTICE_GPT5 = "[Context truncated due to GPT-5 8K input limit]\n\n";

    /** Truncation notice for other models with larger limits. */
    private static final String TRUNCATION_NOTICE_GENERIC = "[Context truncated due to model input limit]\n\n";

    /**
     * Result of streaming response containing the content flux and the provider that handled the request.
     * Surfacing the provider ensures transparency when multiple providers are configured.
     *
     * @param content the streaming response flux
     * @param provider the LLM provider that handled this request
     */
    public record StreamingResult(Flux<String> content, RateLimitService.ApiProvider provider) {
        /** Returns a user-friendly display name for the provider. */
        public String providerDisplayName() {
            return provider.getName();
        }
    }

    private OpenAIClient clientPrimary; // Prefer GitHub Models when available
    private OpenAIClient clientSecondary; // Fallback to OpenAI when available
    private volatile boolean isAvailable = false;
    private final RateLimitService rateLimitManager;
    private final Chunker chunker;
    private final PromptTruncator promptTruncator;

    // When primary (GitHub Models) fails with rate limit/timeout/auth, temporarily avoid using it
    private volatile long primaryBackoffUntilEpochMs = 0L;

    /**
     * Creates a streaming service with rate limiting, chunking, and structured prompt truncation.
     *
     * @param rateLimitManager rate limit state tracker
     * @param chunker token-aware text chunker for legacy string truncation
     * @param promptTruncator structure-aware prompt truncator
     */
    public OpenAIStreamingService(RateLimitService rateLimitManager, Chunker chunker, PromptTruncator promptTruncator) {
        this.rateLimitManager = rateLimitManager;
        this.chunker = chunker;
        this.promptTruncator = promptTruncator;
    }

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${OPENAI_MODEL:gpt-5.2}")
    private String openaiModel;

    @Value("${GITHUB_MODELS_CHAT_MODEL:gpt-5}")
    private String githubModelsChatModel;

    @Value("${GITHUB_MODELS_BASE_URL:https://models.github.ai/inference/v1}")
    private String githubModelsBaseUrl;

    @Value("${OPENAI_REASONING_EFFORT:}")
    private String reasoningEffortSetting;

    @Value("${OPENAI_STREAMING_REQUEST_TIMEOUT_SECONDS:600}")
    private long streamingRequestTimeoutSeconds;

    @Value("${OPENAI_STREAMING_READ_TIMEOUT_SECONDS:75}")
    private long streamingReadTimeoutSeconds;

    @Value("${LLM_PRIMARY_BACKOFF_SECONDS:600}")
    private long primaryBackoffSeconds;

    /**
     * Initializes OpenAI-compatible clients for configured providers after Spring injects credentials.
     */
    @jakarta.annotation.PostConstruct
    public void initializeClient() {
        try {
            // Initialize both when possible; prefer GitHub Models as primary
            if (githubToken != null && !githubToken.isBlank()) {
                log.info("Initializing OpenAI client with GitHub Models endpoint");
                this.clientPrimary = createClient(githubToken, githubModelsBaseUrl);
                log.info("OpenAI client initialized successfully with GitHub Models");
            }
            if (openaiApiKey != null && !openaiApiKey.isBlank()) {
                log.info("Initializing OpenAI client with OpenAI API (fallback)");
                this.clientSecondary = createClient(openaiApiKey, openaiBaseUrl);
                log.info("OpenAI client initialized successfully with OpenAI API");
            }
            this.isAvailable = (clientPrimary != null) || (clientSecondary != null);
            if (!this.isAvailable) {
                log.warn(
                        "No API credentials found (GITHUB_TOKEN or OPENAI_API_KEY) - OpenAI streaming will not be available");
            } else {
                log.info(
                        "OpenAI streaming available (primaryConfigured={}, secondaryConfigured={})",
                        clientPrimary != null,
                        clientSecondary != null);
            }
        } catch (RuntimeException initializationException) {
            log.error("Failed to initialize OpenAI client", initializationException);
            this.isAvailable = (clientPrimary != null) || (clientSecondary != null);
            if (!this.isAvailable) {
                log.warn(
                        "No API credentials found (GITHUB_TOKEN or OPENAI_API_KEY) - OpenAI streaming will not be available");
            }
        }
    }

    /**
     * Closes OpenAI clients during application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        closeClientSafely(clientPrimary, "primary");
        closeClientSafely(clientSecondary, "secondary");
    }

    private void closeClientSafely(OpenAIClient client, String clientName) {
        if (client == null) {
            return;
        }
        try {
            client.close();
            log.debug("Closed OpenAI client");
        } catch (RuntimeException closeException) {
            log.warn("Failed to close OpenAI client");
        }
    }

    private OpenAIClient createClient(String apiKey, String baseUrl) {
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                // Disable SDK-level retries: Reactor timeout and onErrorResume handle failures.
                // Retries cause InterruptedException when Reactor cancels a sleeping retry.
                .maxRetries(0)
                .build();
    }

    /**
     * Stream a response using a structured prompt with intelligent truncation.
     *
     * <p>Truncates the prompt segment-by-segment to preserve semantic boundaries.
     * Context documents are removed first, then older conversation history,
     * while system instructions and the current query are always preserved.</p>
     *
     * <p>Returns a {@link StreamingResult} containing both the content flux and the provider
     * that handled the request. This surfaces provider transparency to callers, ensuring
     * users know which LLM provider is responding (per the no-silent-fallback policy).</p>
     *
     * @param structuredPrompt the typed prompt segments
     * @param temperature the temperature setting for response generation
     * @return a Mono that emits a StreamingResult with content flux and provider info
     */
    public Mono<StreamingResult> streamResponse(StructuredPrompt structuredPrompt, double temperature) {
        log.debug("Starting OpenAI stream with structured prompt");

        return Mono.<StreamingResult>defer(() -> {
                    OpenAIClient streamingClient = selectClientForStreaming();

                    if (streamingClient == null) {
                        String error = "All LLM providers unavailable - check rate limits and API credentials";
                        log.error("[LLM] {}", error);
                        return Mono.<StreamingResult>error(new IllegalStateException(error));
                    }

                    boolean useGitHubModels = isPrimaryClient(streamingClient);
                    RateLimitService.ApiProvider activeProvider = useGitHubModels
                            ? RateLimitService.ApiProvider.GITHUB_MODELS
                            : RateLimitService.ApiProvider.OPENAI;

                    // Determine model and token limits
                    String modelId = normalizedModelId(useGitHubModels);
                    boolean isGpt5 = isGpt5Family(modelId);
                    int tokenLimit = isGpt5 ? MAX_TOKENS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;

                    // Truncate using structure-aware truncator
                    PromptTruncator.TruncatedPrompt truncated =
                            promptTruncator.truncate(structuredPrompt, tokenLimit, isGpt5);
                    String finalPrompt = truncated.render();

                    if (truncated.wasTruncated()) {
                        log.info(
                                "[LLM] Prompt truncated: {} context docs, {} conversation turns",
                                truncated.contextDocumentCount(),
                                truncated.conversationTurnCount());
                    }

                    ResponseCreateParams params = buildResponseParams(finalPrompt, temperature, useGitHubModels);
                    log.info("[LLM] Streaming started (structured, providerId={})", activeProvider.ordinal());

                    Flux<String> contentFlux = executeStreamingRequest(streamingClient, params, activeProvider);
                    return Mono.just(new StreamingResult(contentFlux, activeProvider));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Executes the streaming request and handles completion/error callbacks.
     */
    private Flux<String> executeStreamingRequest(
            OpenAIClient client, ResponseCreateParams params, RateLimitService.ApiProvider activeProvider) {
        RequestOptions requestOptions =
                RequestOptions.builder().timeout(streamingTimeout()).build();

        return Flux.<String, StreamResponse<ResponseStreamEvent>>using(
                        () -> client.responses().createStreaming(params, requestOptions),
                        (StreamResponse<ResponseStreamEvent> responseStream) -> Flux.fromStream(responseStream.stream())
                                .concatMap(event -> Mono.justOrEmpty(extractTextDelta(event))))
                .doOnComplete(() -> {
                    log.debug("[LLM] Stream completed successfully (providerId={})", activeProvider.ordinal());
                    if (rateLimitManager != null) {
                        rateLimitManager.recordSuccess(activeProvider);
                    }
                })
                .doOnError(exception -> {
                    log.error("[LLM] Streaming failed (providerId={})", activeProvider.ordinal());
                    if (rateLimitManager != null) {
                        recordProviderFailure(activeProvider, exception);
                    }
                    maybeBackoffPrimary(client, exception);
                });
    }

    /**
     * Get a complete (non-streaming) response from OpenAI (async wrapper).
     */
    public Mono<String> complete(String prompt, double temperature) {
        final String truncatedPrompt = truncatePromptForModel(prompt);
        return Mono.defer(() -> {
                    OpenAIClient blockingClient = selectClientForBlocking();
                    if (blockingClient == null) {
                        String error = "All LLM providers unavailable - check rate limits and API credentials";
                        log.error("[LLM] {}", error);
                        return Mono.error(new IllegalStateException(error));
                    }
                    boolean useGitHubModels = isPrimaryClient(blockingClient);
                    RateLimitService.ApiProvider activeProvider = useGitHubModels
                            ? RateLimitService.ApiProvider.GITHUB_MODELS
                            : RateLimitService.ApiProvider.OPENAI;
                    ResponseCreateParams params = buildResponseParams(truncatedPrompt, temperature, useGitHubModels);
                    try {
                        log.info("[LLM] Complete started (providerId={})", activeProvider.ordinal());
                        RequestOptions requestOptions = RequestOptions.builder()
                                .timeout(completeTimeout())
                                .build();
                        Response completion = blockingClient.responses().create(params, requestOptions);
                        if (rateLimitManager != null) {
                            rateLimitManager.recordSuccess(activeProvider);
                        }
                        log.debug("[LLM] Complete succeeded (providerId={})", activeProvider.ordinal());
                        String response = extractTextFromResponse(completion);
                        return Mono.just(response);
                    } catch (RuntimeException completionException) {
                        log.error("[LLM] Complete failed (providerId={})", activeProvider.ordinal());
                        if (rateLimitManager != null) {
                            recordProviderFailure(activeProvider, completionException);
                        }
                        maybeBackoffPrimary(blockingClient, completionException);
                        return Mono.error(completionException);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Timeout streamingTimeout() {
        return Timeout.builder()
                .request(java.time.Duration.ofSeconds(Math.max(1, streamingRequestTimeoutSeconds)))
                .read(java.time.Duration.ofSeconds(Math.max(1, streamingReadTimeoutSeconds)))
                .build();
    }

    private Timeout completeTimeout() {
        return Timeout.builder()
                .request(java.time.Duration.ofSeconds(COMPLETE_REQUEST_TIMEOUT_SECONDS))
                .build();
    }

    private void recordProviderFailure(RateLimitService.ApiProvider provider, Throwable throwable) {
        if (!(throwable instanceof OpenAIServiceException serviceException)) {
            if (isRetryablePrimaryFailure(throwable)) {
                rateLimitManager.recordRateLimit(provider, throwable.getMessage());
            }
            return;
        }

        if (serviceException.statusCode() == 429) {
            rateLimitManager.recordRateLimitFromOpenAiServiceException(provider, serviceException);
        }
    }

    private void maybeBackoffPrimary(OpenAIClient client, Throwable throwable) {
        if (!isPrimaryClient(client)) {
            return;
        }
        if (isRetryablePrimaryFailure(throwable)) {
            markPrimaryBackoff();
        }
    }

    private ResponseCreateParams buildResponseParams(String prompt, double temperature, boolean useGitHubModels) {
        String normalizedModelId = normalizedModelId(useGitHubModels);
        boolean gpt5Family = isGpt5Family(normalizedModelId);
        boolean reasoningModel = gpt5Family || normalizedModelId.startsWith("o");

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().input(prompt).model(ResponsesModel.ofString(normalizedModelId));

        if (gpt5Family) {
            // GPT-5 family: omit temperature and set conservative max output tokens
            builder.maxOutputTokens((long) MAX_COMPLETION_TOKENS);
            log.debug("Using GPT-5 family configuration for model: {}", normalizedModelId);

            ReasoningEffort effort = resolveReasoningEffort(normalizedModelId);
            if (effort != null) {
                builder.reasoning(Reasoning.builder().effort(effort).build());
            }
        } else if (!reasoningModel && Double.isFinite(temperature)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private String extractTextDelta(ResponseStreamEvent event) {
        return event.outputTextDelta().map(ResponseTextDeltaEvent::delta).orElse(null);
    }

    private String extractTextFromResponse(Response response) {
        if (response == null) {
            return "";
        }
        StringBuilder outputBuilder = new StringBuilder();
        for (ResponseOutputItem outputItem : response.output()) {
            if (!outputItem.isMessage()) {
                continue;
            }
            ResponseOutputMessage message = outputItem.asMessage();
            for (ResponseOutputMessage.Content content : message.content()) {
                if (content.isOutputText()) {
                    ResponseOutputText outputText = content.asOutputText();
                    outputBuilder.append(outputText.text());
                }
            }
        }
        return outputBuilder.toString();
    }

    // Model mapping removed to prevent unintended regression; use configured model id

    /**
     * Truncate prompt conservatively based on model limits to avoid 413 errors.
     *
     * <p>Uses conservative GPT-5 family limits since both GitHub Models (gpt-5) and
     * OpenAI (gpt-5.2) share the same 8K input constraint.</p>
     */
    private String truncatePromptForModel(String prompt) {
        if (prompt == null || prompt.isEmpty()) return prompt;

        // Both gpt-5 and gpt-5.2 are GPT-5 family with same token limits,
        // so we use conservative limits regardless of which provider is selected later
        String openaiModelId = normalizedModelId(false);
        String githubModelId = normalizedModelId(true);
        boolean isGpt5 = isGpt5Family(openaiModelId) || isGpt5Family(githubModelId);
        boolean isReasoningModel = isGpt5 || openaiModelId.startsWith("o") || githubModelId.startsWith("o");

        int tokenLimit = isReasoningModel ? MAX_TOKENS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;

        String truncated = chunker.keepLastTokens(prompt, tokenLimit);

        if (truncated.length() < prompt.length()) {
            String notice = isGpt5 ? TRUNCATION_NOTICE_GPT5 : TRUNCATION_NOTICE_GENERIC;
            return notice + truncated;
        }

        return prompt;
    }

    /**
     * Returns the normalized model ID for the specified provider.
     *
     * @param useGitHubModels true to use GitHub Models model name, false for OpenAI
     * @return lowercase model ID appropriate for the provider
     */
    private String normalizedModelId(boolean useGitHubModels) {
        String rawModelId;
        String defaultModel;
        if (useGitHubModels) {
            rawModelId = githubModelsChatModel == null ? "" : githubModelsChatModel.trim();
            defaultModel = "gpt-5";
        } else {
            rawModelId = openaiModel == null ? "" : openaiModel.trim();
            defaultModel = "gpt-5.2";
        }
        return rawModelId.isEmpty() ? defaultModel : AsciiTextNormalizer.toLowerAscii(rawModelId);
    }

    private boolean isGpt5Family(String modelId) {
        return modelId != null && modelId.startsWith(GPT_5_MODEL_PREFIX);
    }

    private ReasoningEffort resolveReasoningEffort(String normalizedModelId) {
        if (reasoningEffortSetting == null || reasoningEffortSetting.isBlank()) {
            return null;
        }
        return ReasoningEffort.of(AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim()));
    }

    private String normalizeBaseUrl(String baseUrl) {
        return OpenAiSdkUrlNormalizer.normalize(baseUrl);
    }

    /**
     * Check if the OpenAI streaming service is properly configured and available.
     */
    public boolean isAvailable() {
        return isAvailable && (clientPrimary != null || clientSecondary != null);
    }

    private OpenAIClient selectClientForStreaming() {
        // Prefer OpenAI when available (more reliable, shorter rate limit windows)
        if (clientSecondary != null) {
            if (rateLimitManager == null || rateLimitManager.isProviderAvailable(RateLimitService.ApiProvider.OPENAI)) {
                log.debug(
                        "Selected provider for streaming (providerId={})",
                        RateLimitService.ApiProvider.OPENAI.ordinal());
                return clientSecondary;
            }
        }

        // Try GitHub Models if not in backoff and not rate limited
        if (clientPrimary != null && !isPrimaryInBackoff()) {
            if (rateLimitManager == null
                    || rateLimitManager.isProviderAvailable(RateLimitService.ApiProvider.GITHUB_MODELS)) {
                log.debug(
                        "Selected provider for streaming (providerId={})",
                        RateLimitService.ApiProvider.GITHUB_MODELS.ordinal());
                return clientPrimary;
            }
        }

        // All providers marked as rate limited - try OpenAI anyway (short rate limit windows).
        // Let the API decide if we're actually still rate limited.
        if (clientSecondary != null) {
            log.warn("All providers marked as rate limited; attempting OpenAI anyway "
                    + "(typical rate limits are 1-60 seconds)");
            return clientSecondary;
        }

        // OpenAI not configured - try GitHub Models as last resort
        if (clientPrimary != null) {
            log.warn("All providers marked as rate limited; attempting GitHub Models as last resort");
            return clientPrimary;
        }

        log.error("No LLM clients configured - check OPENAI_API_KEY and GITHUB_TOKEN environment variables");
        return null;
    }

    private OpenAIClient selectClientForBlocking() {
        return selectClientForStreaming();
    }

    private boolean isPrimaryClient(OpenAIClient client) {
        return Objects.equals(client, clientPrimary);
    }

    private boolean isRateLimit(Throwable throwable) {
        return throwable instanceof RateLimitException
                || (throwable instanceof OpenAIServiceException serviceException
                        && serviceException.statusCode() == 429);
    }

    private boolean isRetryablePrimaryFailure(Throwable throwable) {
        if (isRateLimit(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIIoException) {
            return true;
        }
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            return statusCode == 401 || statusCode == 403 || statusCode >= 500;
        }
        String message = throwable.getMessage();
        return message != null && AsciiTextNormalizer.toLowerAscii(message).contains("sleep interrupted");
    }

    private boolean isPrimaryInBackoff() {
        return System.currentTimeMillis() < primaryBackoffUntilEpochMs;
    }

    private void markPrimaryBackoff() {
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, primaryBackoffSeconds));
        this.primaryBackoffUntilEpochMs = until;
        long seconds = Math.max(1, (until - System.currentTimeMillis()) / 1000);
        log.warn("Primary provider temporarily disabled for {}s due to failure", seconds);
    }
}
