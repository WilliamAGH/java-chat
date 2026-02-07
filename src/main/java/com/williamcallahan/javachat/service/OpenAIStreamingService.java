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
import com.williamcallahan.javachat.web.SseConstants;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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

    private static final String PROVIDER_SETTING_OPENAI = "openai";
    private static final String PROVIDER_SETTING_GITHUB_MODELS = "github_models";
    private static final String PROVIDER_SETTING_GITHUB_MODELS_ALT = "github-models";
    private static final String PROVIDER_SETTING_GITHUB = "github";
    private static final String STREAM_STATUS_CODE_PROVIDER_FALLBACK =
            SseConstants.STATUS_CODE_STREAM_PROVIDER_FALLBACK;
    private static final String STREAM_STAGE_STREAM = SseConstants.STATUS_STAGE_STREAM;

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    /**
     * Result of streaming response containing the content flux and the provider that handled the request.
     * Surfacing the provider ensures transparency when multiple providers are configured.
     *
     * @param textChunks the streaming response flux
     * @param provider the initially selected LLM provider for this request
     * @param notices runtime streaming notices (for example, pre-first-token provider failover)
     */
    public record StreamingResult(
            Flux<String> textChunks, RateLimitService.ApiProvider provider, Flux<StreamingNotice> notices) {
        /** Returns a user-friendly display name for the provider. */
        public String providerDisplayName() {
            return provider.getName();
        }
    }

    /**
     * Structured runtime notice emitted during streaming.
     *
     * @param summary short user-facing summary
     * @param diagnosticContext detailed context for UI status indicators
     * @param code stable machine-readable status code
     * @param retryable whether this notice represents a retryable condition
     * @param provider provider label associated with this notice
     * @param stage processing stage for UI grouping
     * @param attempt current attempt number (1-based)
     * @param maxAttempts max configured attempts for this request
     */
    public record StreamingNotice(
            String summary,
            String diagnosticContext,
            String code,
            boolean retryable,
            String provider,
            String stage,
            int attempt,
            int maxAttempts) {
        public StreamingNotice {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary cannot be null or blank");
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code cannot be null or blank");
            }
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("stage cannot be null or blank");
            }
            if (attempt <= 0) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            if (maxAttempts <= 0 || attempt > maxAttempts) {
                throw new IllegalArgumentException("attempt must be <= maxAttempts");
            }
            diagnosticContext = diagnosticContext == null ? "" : diagnosticContext;
            provider = provider == null ? "" : provider;
        }
    }

    /**
     * Provider candidate used for deterministic fallback ordering.
     *
     * @param client OpenAI-compatible client for the provider
     * @param provider provider metadata used for logging and rate-limit tracking
     */
    private record ProviderCandidate(OpenAIClient client, RateLimitService.ApiProvider provider) {}

    /**
     * Prepared per-provider streaming request with prompt truncation metadata.
     *
     * @param responseParams request parameters for the selected provider/model
     * @param modelId normalized model id used for this attempt
     */
    private record PreparedStreamingRequest(ResponseCreateParams responseParams, String modelId) {}

    /** GitHub Models client when configured. */
    private OpenAIClient clientPrimary;

    /** OpenAI direct client when configured. */
    private OpenAIClient clientSecondary;

    private volatile boolean isAvailable = false;
    private final RateLimitService rateLimitManager;
    private final Chunker chunker;
    private final PromptTruncator promptTruncator;

    /** Epoch millis until which the primary provider (GitHub Models) is temporarily avoided after failure. */
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

    @Value("${LLM_PRIMARY_PROVIDER:github_models}")
    private String primaryProviderSetting;

    /**
     * Initializes OpenAI-compatible clients for configured providers after Spring injects credentials.
     *
     * <p>Fails fast on initialization errors — a partially configured LLM service must not
     * silently degrade at runtime. Missing credentials are not errors (the service reports
     * itself as unavailable); initialization failures during client construction are.</p>
     */
    @jakarta.annotation.PostConstruct
    public void initializeClient() {
        if (githubToken != null && !githubToken.isBlank()) {
            log.info("Initializing OpenAI client with GitHub Models endpoint");
            this.clientPrimary = createClient(githubToken, githubModelsBaseUrl);
            log.info("OpenAI client initialized successfully with GitHub Models");
        }
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            log.info("Initializing OpenAI client with OpenAI API");
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
            log.debug("Closed OpenAI client (clientName={})", clientName);
        } catch (RuntimeException closeException) {
            log.warn("Failed to close OpenAI client (clientName={})", clientName, closeException);
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
                    List<ProviderCandidate> availableProviders = selectAvailableProviderCandidates();
                    if (availableProviders.isEmpty()) {
                        String unavailableReason =
                                "LLM providers unavailable - active provider is rate limited or misconfigured";
                        log.error("[LLM] {}", unavailableReason);
                        return Mono.<StreamingResult>error(new IllegalStateException(unavailableReason));
                    }

                    ProviderCandidate initialProvider = availableProviders.get(0);
                    Sinks.Many<StreamingNotice> noticeSink =
                            Sinks.many().multicast().onBackpressureBuffer();
                    StreamingAttemptContext attemptContext =
                            new StreamingAttemptContext(availableProviders, 0, noticeSink);
                    Flux<String> textChunksFlux = executeStreamingWithProviderFallback(
                                    structuredPrompt, temperature, attemptContext)
                            .doFinally(ignoredSignal -> noticeSink.tryEmitComplete());
                    return Mono.just(new StreamingResult(textChunksFlux, initialProvider.provider(), noticeSink.asFlux()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Tracks the mutable state of a provider-fallback streaming attempt chain.
     *
     * @param availableProviders ordered list of providers to try
     * @param providerIndex index of the current provider in the ordered list
     * @param noticeSink sink for runtime streaming notices emitted during fallback
     */
    private record StreamingAttemptContext(
            List<ProviderCandidate> availableProviders, int providerIndex, Sinks.Many<StreamingNotice> noticeSink) {
        StreamingAttemptContext {
            Objects.requireNonNull(availableProviders, "availableProviders");
            Objects.requireNonNull(noticeSink, "noticeSink");
        }

        ProviderCandidate currentProvider() {
            return availableProviders.get(providerIndex);
        }

        int currentAttempt() {
            return providerIndex + 1;
        }

        int maxAttempts() {
            return availableProviders.size();
        }

        boolean hasNextProvider() {
            return providerIndex + 1 < availableProviders.size();
        }

        StreamingAttemptContext withNextProvider() {
            return new StreamingAttemptContext(availableProviders, providerIndex + 1, noticeSink);
        }
    }

    private Flux<String> executeStreamingWithProviderFallback(
            StructuredPrompt structuredPrompt, double temperature, StreamingAttemptContext attemptContext) {
        ProviderCandidate providerCandidate = attemptContext.currentProvider();
        RateLimitService.ApiProvider activeProvider = providerCandidate.provider();
        PreparedStreamingRequest preparedStreamingRequest =
                prepareStreamingRequest(structuredPrompt, temperature, activeProvider);
        AtomicBoolean emittedTextChunk = new AtomicBoolean(false);

        log.info(
                "[LLM] Streaming started (structured, providerId={}, attempt={}/{}, model={})",
                activeProvider.ordinal(),
                attemptContext.currentAttempt(),
                attemptContext.maxAttempts(),
                preparedStreamingRequest.modelId());

        return executeStreamingRequest(
                        providerCandidate.client(), preparedStreamingRequest.responseParams(), activeProvider)
                .doOnNext(ignoredChunk -> emittedTextChunk.set(true))
                .onErrorResume(streamingFailure -> {
                    if (!attemptContext.hasNextProvider()
                            || emittedTextChunk.get()
                            || !isStreamingFallbackEligible(streamingFailure)) {
                        return Flux.error(streamingFailure);
                    }

                    StreamingAttemptContext nextAttempt = attemptContext.withNextProvider();
                    ProviderCandidate fallbackProvider = nextAttempt.currentProvider();
                    log.warn(
                            "[LLM] Falling back to providerId={} before first chunk "
                                    + "(failedProviderId={}, attempt={}/{}, exceptionType={})",
                            fallbackProvider.provider().ordinal(),
                            activeProvider.ordinal(),
                            nextAttempt.currentAttempt(),
                            nextAttempt.maxAttempts(),
                            streamingFailure.getClass().getSimpleName());

                    emitStreamingNotice(
                            attemptContext.noticeSink(),
                            new StreamingNotice(
                                    "Retrying stream with alternate provider",
                                    "The API returned an invalid or transient streaming response. "
                                            + "Retrying before any response text was emitted.",
                                    STREAM_STATUS_CODE_PROVIDER_FALLBACK,
                                    true,
                                    fallbackProvider.provider().getName(),
                                    STREAM_STAGE_STREAM,
                                    nextAttempt.currentAttempt(),
                                    nextAttempt.maxAttempts()));

                    return executeStreamingWithProviderFallback(structuredPrompt, temperature, nextAttempt);
                });
    }

    private PreparedStreamingRequest prepareStreamingRequest(
            StructuredPrompt structuredPrompt, double temperature, RateLimitService.ApiProvider provider) {
        boolean useGitHubModels = provider == RateLimitService.ApiProvider.GITHUB_MODELS;
        String modelId = normalizedModelId(useGitHubModels);
        boolean isGpt5 = isGpt5Family(modelId);
        int tokenLimit = isGpt5 ? MAX_TOKENS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;

        PromptTruncator.TruncatedPrompt truncatedPrompt =
                promptTruncator.truncate(structuredPrompt, tokenLimit, isGpt5);
        if (truncatedPrompt.wasTruncated()) {
            log.info(
                    "[LLM] Prompt truncated for streaming (providerId={}, model={}, contextDocs={}, conversationTurns={})",
                    provider.ordinal(),
                    modelId,
                    truncatedPrompt.contextDocumentCount(),
                    truncatedPrompt.conversationTurnCount());
        }

        ResponseCreateParams responseParams = buildResponseParams(truncatedPrompt.render(), temperature, modelId);
        return new PreparedStreamingRequest(responseParams, modelId);
    }

    /**
     * Executes the streaming request and handles completion/error callbacks.
     */
    private Flux<String> executeStreamingRequest(
            OpenAIClient client, ResponseCreateParams requestParameters, RateLimitService.ApiProvider activeProvider) {
        RequestOptions requestOptions =
                RequestOptions.builder().timeout(streamingTimeout()).build();

        return Flux.<String, StreamResponse<ResponseStreamEvent>>using(
                        () -> client.responses().createStreaming(requestParameters, requestOptions),
                        (StreamResponse<ResponseStreamEvent> responseStream) -> Flux.fromStream(responseStream.stream())
                                .concatMap(event -> Mono.justOrEmpty(extractTextDelta(event))))
                .doOnComplete(() -> {
                    log.debug("[LLM] Stream completed successfully (providerId={})", activeProvider.ordinal());
                    rateLimitManager.recordSuccess(activeProvider);
                })
                .doOnError(exception -> {
                    log.error("[LLM] Streaming failed (providerId={})", activeProvider.ordinal(), exception);
                    recordProviderFailure(activeProvider, exception);
                    maybeBackoffPrimary(activeProvider, exception);
                });
    }

    /**
     * Sends a non-streaming completion request to the best available provider.
     *
     * <p>Selects providers in priority order and attempts each configured provider at most once
     * within the same call. Fallback activates only for provider-availability failures
     * (for example: rate limit, auth, I/O, timeout, upstream 5xx), preserving immediate
     * propagation for deterministic client-side request errors.</p>
     */
    public Mono<String> complete(String prompt, double temperature) {
        final String truncatedPrompt = truncatePromptForModel(prompt);
        return Mono.<String>defer(() -> {
                    List<ProviderCandidate> availableProviders = selectAvailableProviderCandidates();
                    if (availableProviders.isEmpty()) {
                        String unavailableReason =
                                "LLM providers unavailable - active provider is rate limited or misconfigured";
                        log.error("[LLM] {}", unavailableReason);
                        return Mono.error(new IllegalStateException(unavailableReason));
                    }

                    RuntimeException lastProviderFailure = null;
                    for (int providerIndex = 0; providerIndex < availableProviders.size(); providerIndex++) {
                        ProviderCandidate providerCandidate = availableProviders.get(providerIndex);
                        RateLimitService.ApiProvider activeProvider = providerCandidate.provider();
                        String modelId =
                                normalizedModelId(activeProvider == RateLimitService.ApiProvider.GITHUB_MODELS);

                        ResponseCreateParams requestParameters =
                                buildResponseParams(truncatedPrompt, temperature, modelId);
                        try {
                            log.info("[LLM] Complete started (providerId={})", activeProvider.ordinal());
                            RequestOptions requestOptions = RequestOptions.builder()
                                    .timeout(completeTimeout())
                                    .build();
                            Response completion =
                                    providerCandidate.client().responses().create(requestParameters, requestOptions);
                            rateLimitManager.recordSuccess(activeProvider);
                            log.debug("[LLM] Complete succeeded (providerId={})", activeProvider.ordinal());
                            String completionText = extractTextFromResponse(completion);
                            return Mono.just(completionText);
                        } catch (RuntimeException completionException) {
                            lastProviderFailure = completionException;
                            log.error(
                                    "[LLM] Complete failed (providerId={})",
                                    activeProvider.ordinal(),
                                    completionException);
                            recordProviderFailure(activeProvider, completionException);
                            maybeBackoffPrimary(activeProvider, completionException);

                            boolean hasNextProvider = providerIndex + 1 < availableProviders.size();
                            if (hasNextProvider && isCompletionFallbackEligible(completionException)) {
                                log.warn(
                                        "[LLM] Falling back to secondary provider after complete failure "
                                                + "(providerId={}, exceptionType={})",
                                        activeProvider.ordinal(),
                                        completionException.getClass().getSimpleName());
                                continue;
                            }
                            return Mono.error(completionException);
                        }
                    }

                    if (lastProviderFailure != null) {
                        return Mono.error(lastProviderFailure);
                    }
                    return Mono.error(new IllegalStateException("No provider attempt was executed for completion"));
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
            return;
        }

        if (serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS) {
            rateLimitManager.recordRateLimitFromOpenAiServiceException(provider, serviceException);
        }
    }

    private void maybeBackoffPrimary(RateLimitService.ApiProvider provider, Throwable throwable) {
        if (provider != configuredPrimaryProvider()) {
            return;
        }
        if (shouldBackoffPrimary(throwable)) {
            markPrimaryBackoff();
        }
    }

    private ResponseCreateParams buildResponseParams(String prompt, double temperature, String normalizedModelId) {
        boolean gpt5Family = isGpt5Family(normalizedModelId);
        boolean reasoningModel = gpt5Family || normalizedModelId.startsWith("o");

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().input(prompt).model(ResponsesModel.ofString(normalizedModelId));

        if (gpt5Family) {
            // GPT-5 family: omit temperature and set conservative max output tokens
            builder.maxOutputTokens((long) MAX_COMPLETION_TOKENS);
            log.debug("Using GPT-5 family configuration for model: {}", normalizedModelId);

            resolveReasoningEffort()
                    .ifPresent(effort ->
                            builder.reasoning(Reasoning.builder().effort(effort).build()));
        } else if (!reasoningModel && Double.isFinite(temperature)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private Optional<String> extractTextDelta(ResponseStreamEvent event) {
        return event.outputTextDelta().map(ResponseTextDeltaEvent::delta);
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
            for (ResponseOutputMessage.Content messageContent : message.content()) {
                if (messageContent.isOutputText()) {
                    ResponseOutputText outputText = messageContent.asOutputText();
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
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }

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

    private Optional<ReasoningEffort> resolveReasoningEffort() {
        if (reasoningEffortSetting == null || reasoningEffortSetting.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ReasoningEffort.of(AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim())));
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

    private List<ProviderCandidate> selectAvailableProviderCandidates() {
        List<ProviderCandidate> orderedCandidates = orderedProviderCandidates();
        List<ProviderCandidate> availableCandidates = new ArrayList<>(orderedCandidates.size());
        for (ProviderCandidate providerCandidate : orderedCandidates) {
            if (isProviderCandidateAvailable(providerCandidate)) {
                availableCandidates.add(providerCandidate);
            }
        }
        return List.copyOf(availableCandidates);
    }

    private List<ProviderCandidate> orderedProviderCandidates() {
        RateLimitService.ApiProvider configuredPrimary = configuredPrimaryProvider();
        RateLimitService.ApiProvider configuredSecondary =
                configuredPrimary == RateLimitService.ApiProvider.GITHUB_MODELS
                        ? RateLimitService.ApiProvider.OPENAI
                        : RateLimitService.ApiProvider.GITHUB_MODELS;

        List<ProviderCandidate> candidates = new ArrayList<>(2);
        addProviderCandidate(candidates, configuredPrimary);
        addProviderCandidate(candidates, configuredSecondary);
        return List.copyOf(candidates);
    }

    private void addProviderCandidate(List<ProviderCandidate> candidates, RateLimitService.ApiProvider provider) {
        OpenAIClient providerClient = providerClient(provider);
        if (providerClient == null) {
            return;
        }
        candidates.add(new ProviderCandidate(providerClient, provider));
    }

    private OpenAIClient providerClient(RateLimitService.ApiProvider provider) {
        return switch (provider) {
            case GITHUB_MODELS -> clientPrimary;
            case OPENAI -> clientSecondary;
            case LOCAL -> null;
        };
    }

    private boolean isProviderCandidateAvailable(ProviderCandidate providerCandidate) {
        RateLimitService.ApiProvider provider = providerCandidate.provider();
        if (provider == configuredPrimaryProvider() && isPrimaryInBackoff()) {
            log.warn("Primary provider unavailable (backoff active, providerId={})", provider.ordinal());
            return false;
        }
        if (rateLimitManager.isProviderAvailable(provider)) {
            return true;
        }
        log.warn("Provider unavailable (rate limited, providerId={})", provider.ordinal());
        return false;
    }

    private RateLimitService.ApiProvider configuredPrimaryProvider() {
        String normalizedSetting = primaryProviderSetting == null
                ? PROVIDER_SETTING_GITHUB_MODELS
                : AsciiTextNormalizer.toLowerAscii(primaryProviderSetting.trim());
        return switch (normalizedSetting) {
            case PROVIDER_SETTING_OPENAI -> RateLimitService.ApiProvider.OPENAI;
            case PROVIDER_SETTING_GITHUB_MODELS, PROVIDER_SETTING_GITHUB_MODELS_ALT, PROVIDER_SETTING_GITHUB ->
                RateLimitService.ApiProvider.GITHUB_MODELS;
            default -> {
                log.warn(
                        "Unknown LLM_PRIMARY_PROVIDER value '{}', defaulting to '{}'",
                        primaryProviderSetting,
                        PROVIDER_SETTING_GITHUB_MODELS);
                yield RateLimitService.ApiProvider.GITHUB_MODELS;
            }
        };
    }

    private boolean isRateLimit(Throwable throwable) {
        return throwable instanceof RateLimitException
                || (throwable instanceof OpenAIServiceException serviceException
                        && serviceException.statusCode() == HTTP_TOO_MANY_REQUESTS);
    }

    /**
     * Determines whether a failure should trigger temporary backoff of the primary provider.
     *
     * <p>Returns true for transient failures (rate limits, I/O errors, server errors)
     * where backing off the primary allows subsequent calls to route to the secondary.
     * Returns false for client-side errors (bad request, invalid arguments) where
     * switching providers would not help.</p>
     */
    private boolean shouldBackoffPrimary(Throwable throwable) {
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
            return statusCode == HTTP_UNAUTHORIZED
                    || statusCode == HTTP_FORBIDDEN
                    || statusCode >= HTTP_INTERNAL_SERVER_ERROR;
        }
        String message = throwable.getMessage();
        return message != null && AsciiTextNormalizer.toLowerAscii(message).contains("sleep interrupted");
    }

    private boolean isCompletionFallbackEligible(Throwable throwable) {
        if (shouldBackoffPrimary(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() == HTTP_NOT_FOUND
                    || serviceException.statusCode() == HTTP_REQUEST_TIMEOUT;
        }
        String exceptionMessage = throwable.getMessage();
        if (exceptionMessage == null) {
            return false;
        }
        String normalizedMessage = AsciiTextNormalizer.toLowerAscii(exceptionMessage);
        return normalizedMessage.contains("timeout")
                || normalizedMessage.contains("temporarily unavailable")
                || normalizedMessage.contains("connection reset")
                || normalizedMessage.contains("connection closed");
    }

    private boolean isStreamingFallbackEligible(Throwable throwable) {
        if (shouldBackoffPrimary(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            int statusCode = serviceException.statusCode();
            return statusCode == HTTP_REQUEST_TIMEOUT
                    || statusCode == HTTP_CONFLICT
                    || statusCode == HTTP_TOO_MANY_REQUESTS
                    || statusCode >= HTTP_INTERNAL_SERVER_ERROR;
        }
        String exceptionType = throwable.getClass().getSimpleName();
        if ("SseException".equals(exceptionType) || "OverflowException".equals(exceptionType)) {
            return true;
        }
        String exceptionMessage = throwable.getMessage();
        if (exceptionMessage == null) {
            return false;
        }
        String normalizedMessage = AsciiTextNormalizer.toLowerAscii(exceptionMessage);
        return normalizedMessage.contains("overflowexception")
                || normalizedMessage.contains("invalid stream")
                || normalizedMessage.contains("malformed")
                || normalizedMessage.contains("unexpected end of json input")
                || normalizedMessage.contains("timeout")
                || normalizedMessage.contains("temporarily unavailable")
                || normalizedMessage.contains("connection reset")
                || normalizedMessage.contains("connection closed");
    }

    /**
     * Returns whether a streaming failure is likely recoverable with a retry.
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        return isStreamingFallbackEligible(throwable);
    }

    private boolean isPrimaryInBackoff() {
        return System.currentTimeMillis() < primaryBackoffUntilEpochMs;
    }

    private void markPrimaryBackoff() {
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, primaryBackoffSeconds));
        this.primaryBackoffUntilEpochMs = until;
        long seconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(until - System.currentTimeMillis()));
        log.warn("Primary provider temporarily disabled for {}s due to failure", seconds);
    }

    private void emitStreamingNotice(Sinks.Many<StreamingNotice> noticeSink, StreamingNotice streamingNotice) {
        Sinks.EmitResult emitResult = noticeSink.tryEmitNext(streamingNotice);
        if (emitResult.isFailure()) {
            log.debug("Failed to emit streaming notice (emitResult={})", emitResult);
        }
    }
}
