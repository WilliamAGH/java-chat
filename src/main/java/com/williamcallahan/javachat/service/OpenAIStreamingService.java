package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.williamcallahan.javachat.application.completion.CompletionRequestConfiguration;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import com.williamcallahan.javachat.web.SseConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 * Streams and completes chat responses using OpenAI-compatible providers.
 *
 * <p>This service orchestrates SDK transport, provider retries before first token,
 * and SSE-facing runtime notices while delegating provider policy and request
 * construction to focused collaborators.</p>
 */
@Service
public class OpenAIStreamingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingService.class);

    private static final String STREAM_STATUS_CODE_PROVIDER_FALLBACK =
            SseConstants.STATUS_CODE_STREAM_PROVIDER_FALLBACK;
    private static final String STREAM_STAGE_STREAM = SseConstants.STATUS_STAGE_STREAM;

    /** GitHub Models client when configured. */
    private OpenAIClient clientPrimary;

    /** OpenAI-compatible client when configured. */
    private OpenAIClient clientSecondary;

    private volatile boolean isAvailable;
    private final RateLimitService rateLimitService;
    private final OpenAiRequestFactory requestFactory;
    private final OpenAiProviderRoutingService providerRoutingService;
    private final StreamingFailureReporter streamingFailureReporter;

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${GITHUB_MODELS_BASE_URL:https://models.github.ai/inference/v1}")
    private String githubModelsBaseUrl;

    @Value("${OPENAI_STREAMING_REQUEST_TIMEOUT_SECONDS:600}")
    private long streamingRequestTimeoutSeconds;

    @Value("${OPENAI_STREAMING_READ_TIMEOUT_SECONDS:75}")
    private long streamingReadTimeoutSeconds;

    /**
     * LLM-gateway priority class sent as the {@code X-Tier} header on live chat
     * turns. The gateway queue treats untagged requests as {@code default}
     * (priority 5, zero reserved slots), which can starve live traffic behind
     * batch jobs; {@code production-z} is java-chat's recognized live tier.
     * Background/batch callers must use {@code batch} instead. Harmless on
     * non-gateway upstreams (OpenAI direct, GitHub Models), which ignore it.
     */
    @Value("${LLM_GATEWAY_TIER:production-z}")
    private String llmGatewayTier;

    /**
     * Creates a streaming service with explicit dependencies for routing and payload construction.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param requestFactory request payload and truncation builder
     * @param providerRoutingService provider ordering and fallback classifier
     * @param streamingFailureReporter terminal provider-failure boundary
     */
    public OpenAIStreamingService(
            RateLimitService rateLimitService,
            OpenAiRequestFactory requestFactory,
            OpenAiProviderRoutingService providerRoutingService,
            StreamingFailureReporter streamingFailureReporter) {
        this.rateLimitService = rateLimitService;
        this.requestFactory = requestFactory;
        this.providerRoutingService = providerRoutingService;
        this.streamingFailureReporter = streamingFailureReporter;
        this.isAvailable = false;
    }

    /**
     * Initializes OpenAI-compatible clients for configured providers after Spring injects credentials.
     */
    @PostConstruct
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
                    "OpenAI streaming available (githubModelsConfigured={}, openAiCompatibleConfigured={})",
                    clientPrimary != null,
                    clientSecondary != null);
        }
    }

    /** Closes OpenAI clients during application shutdown. */
    @PreDestroy
    public void shutdown() {
        closeClientSafely(clientPrimary, "primary");
        closeClientSafely(clientSecondary, "secondary");
    }

    /**
     * Streams a response using a structured prompt with bounded retries before first text.
     *
     * @param structuredPrompt typed prompt segments
     * @param temperature response temperature
     * @return stream result including text chunks, selected provider, and notices
     */
    public Mono<StreamingResult> streamResponse(StructuredPrompt structuredPrompt, double temperature) {
        log.debug("Starting OpenAI stream with structured prompt");

        return Mono.<StreamingResult>defer(() -> {
                    List<OpenAiProviderCandidate> availableProviders =
                            providerRoutingService.selectAvailableProviderCandidates(clientPrimary, clientSecondary);
                    if (availableProviders.isEmpty()) {
                        String unavailableReason =
                                "LLM providers unavailable - active provider is rate limited or misconfigured";
                        log.warn("[LLM] {}", unavailableReason);
                        return Mono.<StreamingResult>error(new IllegalStateException(unavailableReason));
                    }

                    OpenAiProviderCandidate initialProvider = availableProviders.get(0);
                    // Routing selects two candidates, so one pre-text fallback signal is sufficient.
                    Sinks.Many<StreamingNotice> noticeSink =
                            Sinks.many().replay().limit(1);
                    StreamingAttemptContext attemptContext =
                            StreamingAttemptContext.first(availableProviders, noticeSink);
                    Flux<String> contentFlux = executeStreamingWithPreTextRetry(
                                    structuredPrompt, temperature, attemptContext)
                            .doFinally(ignoredSignalType -> noticeSink.tryEmitComplete());
                    return Mono.just(new StreamingResult(contentFlux, initialProvider.provider(), noticeSink.asFlux()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sends a non-streaming completion request to the best available provider.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @return completion text from the first successful provider attempt
     */
    public Mono<String> complete(String prompt, double temperature) {
        return executeCompletion(prompt, temperature, CompletionRequestConfiguration.defaultText());
    }

    /**
     * Sends a non-streaming completion request with an explicit output budget.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return completion text from the first successful provider attempt
     */
    public Mono<String> complete(String prompt, double temperature, int maximumOutputTokens) {
        return Mono.defer(() -> executeCompletion(
                prompt, temperature, CompletionRequestConfiguration.boundedText(maximumOutputTokens)));
    }

    /**
     * Sends a non-streaming completion request that requires a JSON object response.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return completion text from the first provider honoring the JSON contract
     */
    public Mono<String> completeJsonObject(String prompt, double temperature, int maximumOutputTokens) {
        return completeJsonObject(
                prompt, temperature, maximumOutputTokens, CompletionRequestConfiguration.defaultRequestTimeout());
    }

    /**
     * Sends a non-streaming completion request that requires a JSON object response within a caller-owned timeout.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @param requestTimeout whole-request timeout owned by this caller
     * @return completion text from the first provider honoring the JSON contract
     */
    public Mono<String> completeJsonObject(
            String prompt, double temperature, int maximumOutputTokens, Duration requestTimeout) {
        return Mono.defer(() -> executeCompletion(
                prompt, temperature, CompletionRequestConfiguration.jsonObject(maximumOutputTokens, requestTimeout)));
    }

    private Mono<String> executeCompletion(
            String prompt, double temperature, CompletionRequestConfiguration configuration) {
        return Mono.<String>defer(() -> {
                    List<OpenAiProviderCandidate> availableProviders =
                            providerRoutingService.selectAvailableProviderCandidates(clientPrimary, clientSecondary);
                    if (availableProviders.isEmpty()) {
                        String unavailableReason =
                                "LLM providers unavailable - active provider is rate limited or misconfigured";
                        log.error("[LLM] {}", unavailableReason);
                        return Mono.error(new IllegalStateException(unavailableReason));
                    }

                    RuntimeException lastProviderFailure = null;
                    for (int providerIndex = 0; providerIndex < availableProviders.size(); providerIndex++) {
                        OpenAiProviderCandidate providerCandidate = availableProviders.get(providerIndex);
                        RateLimitService.ApiProvider activeProvider = providerCandidate.provider();

                        ResponseCreateParams requestParameters =
                                buildCompletionRequest(prompt, temperature, activeProvider, configuration);
                        try {
                            log.info("[LLM] Complete started (providerId={})", activeProvider.ordinal());
                            RequestOptions requestOptions = RequestOptions.builder()
                                    .timeout(completeTimeout(configuration.requestTimeout()))
                                    .build();
                            Response completion =
                                    providerCandidate.client().responses().create(requestParameters, requestOptions);
                            rateLimitService.recordSuccess(activeProvider);
                            log.debug("[LLM] Complete succeeded (providerId={})", activeProvider.ordinal());
                            return Mono.just(extractTextFromResponse(completion));
                        } catch (RuntimeException completionException) {
                            lastProviderFailure = completionException;
                            providerRoutingService.recordProviderFailure(activeProvider, completionException);

                            boolean hasNextProvider = providerIndex + 1 < availableProviders.size();
                            if (hasNextProvider
                                    && providerRoutingService.isCompletionFallbackEligible(completionException)) {
                                log.warn(
                                        "[LLM] Complete attempt failed; falling back to secondary provider "
                                                + "(providerId={}, exceptionType={})",
                                        activeProvider.ordinal(),
                                        completionException.getClass().getSimpleName());
                                continue;
                            }
                            log.error(
                                    "[LLM] Complete failed (providerId={})",
                                    activeProvider.ordinal(),
                                    completionException);
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

    private ResponseCreateParams buildCompletionRequest(
            String prompt,
            double temperature,
            RateLimitService.ApiProvider activeProvider,
            CompletionRequestConfiguration configuration) {
        if (configuration.requireJsonObject()) {
            return requestFactory.buildJsonCompletionRequest(
                    prompt,
                    temperature,
                    activeProvider,
                    configuration.maximumOutputTokens().orElseThrow());
        }
        if (configuration.maximumOutputTokens().isEmpty()) {
            return requestFactory.buildCompletionRequest(prompt, temperature, activeProvider);
        }
        return requestFactory.buildCompletionRequest(
                prompt,
                temperature,
                activeProvider,
                configuration.maximumOutputTokens().orElseThrow());
    }

    /**
     * Returns whether a streaming failure is likely recoverable with a retry.
     *
     * @param throwable streaming failure
     * @return true when fallback conditions indicate transient failure
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        Throwable upstreamFailure = ReportedStreamingFailure.findInCauseChain(throwable)
                .map(ReportedStreamingFailure::upstreamFailure)
                .orElse(throwable);
        return providerRoutingService.isRecoverableStreamingFailure(upstreamFailure);
    }

    private Flux<String> executeStreamingWithPreTextRetry(
            StructuredPrompt structuredPrompt, double temperature, StreamingAttemptContext attemptContext) {
        OpenAiProviderCandidate providerCandidate = attemptContext.currentProvider();
        RateLimitService.ApiProvider activeProvider = providerCandidate.provider();
        OpenAiPreparedRequest preparedStreamingRequest =
                requestFactory.prepareStreamingRequest(structuredPrompt, temperature, activeProvider);
        AtomicBoolean emittedTextChunk = new AtomicBoolean(false);

        log.info(
                "[LLM] Streaming started (structured, providerId={}, attempt={}/{}, model={})",
                activeProvider.ordinal(),
                attemptContext.currentAttempt(),
                attemptContext.maxAttempts(),
                preparedStreamingRequest.modelId());

        return executeStreamingRequest(
                        providerCandidate.client(), preparedStreamingRequest.responseParams(), activeProvider)
                .doOnNext(textChunk -> {
                    if (!textChunk.isEmpty()) {
                        emittedTextChunk.set(true);
                    }
                })
                .onErrorResume(streamingFailure -> {
                    if (!attemptContext.hasNextAttempt()
                            || emittedTextChunk.get()
                            || !providerRoutingService.isStreamingFallbackEligible(streamingFailure)) {
                        return Flux.error(streamingFailureReporter.reportTerminalFailure(
                                streamingFailure,
                                new StreamingFailureReporter.TerminalAttempt(
                                        activeProvider.getName(),
                                        preparedStreamingRequest.modelId(),
                                        attemptContext.currentAttempt(),
                                        attemptContext.maxAttempts(),
                                        emittedTextChunk.get())));
                    }

                    StreamingAttemptContext nextAttempt = attemptContext.withNextAttempt();
                    OpenAiProviderCandidate retryProvider = nextAttempt.currentProvider();
                    log.warn(
                            "[LLM] Retrying with providerId={} before first chunk "
                                    + "(failedProviderId={}, attempt={}/{}, exceptionType={})",
                            retryProvider.provider().ordinal(),
                            activeProvider.ordinal(),
                            nextAttempt.currentAttempt(),
                            nextAttempt.maxAttempts(),
                            streamingFailure.getClass().getSimpleName());

                    emitStreamingNotice(
                            attemptContext.noticeSink(),
                            StreamingNotice.builder(
                                            "Retrying stream with provider", STREAM_STATUS_CODE_PROVIDER_FALLBACK)
                                    .diagnosticContext("The API returned an invalid or transient streaming response. "
                                            + "Retrying before any response text was emitted.")
                                    .retryable(true)
                                    .origin(new StreamingNoticeOrigin(
                                            retryProvider.provider().getName(),
                                            STREAM_STAGE_STREAM,
                                            nextAttempt.currentAttempt(),
                                            nextAttempt.maxAttempts()))
                                    .build());

                    return executeStreamingWithPreTextRetry(structuredPrompt, temperature, nextAttempt);
                });
    }

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
                    rateLimitService.recordSuccess(activeProvider);
                })
                .doOnError(exception -> {
                    providerRoutingService.recordProviderFailure(activeProvider, exception);
                });
    }

    private Timeout streamingTimeout() {
        return Timeout.builder()
                .request(Duration.ofSeconds(Math.max(1, streamingRequestTimeoutSeconds)))
                .read(Duration.ofSeconds(Math.max(1, streamingReadTimeoutSeconds)))
                .build();
    }

    private Timeout completeTimeout(Duration requestTimeout) {
        return Timeout.builder().request(requestTimeout).build();
    }

    private OpenAIClient createClient(String apiKey, String baseUrl) {
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(OpenAiSdkUrlNormalizer.normalize(baseUrl))
                .putHeader(LlmGatewayTier.REQUEST_TIER_HEADER, resolvedLlmGatewayTier())
                // Caller-owned request timeouts and provider routing own failure handling.
                // SDK retry sleeps interfere with reactive cancellation.
                .maxRetries(0)
                .build();
    }

    private String resolvedLlmGatewayTier() {
        return llmGatewayTier == null || llmGatewayTier.isBlank()
                ? LlmGatewayTier.LIVE.requestHeader()
                : llmGatewayTier.trim();
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

    /**
     * Check if the OpenAI streaming service is properly configured and available.
     *
     * @return true when at least one provider client is configured
     */
    public boolean isAvailable() {
        return isAvailable && (clientPrimary != null || clientSecondary != null);
    }

    private void emitStreamingNotice(Sinks.Many<StreamingNotice> noticeSink, StreamingNotice streamingNotice) {
        Sinks.EmitResult emitResult = noticeSink.tryEmitNext(streamingNotice);
        if (emitResult.isFailure()) {
            log.debug("Failed to emit streaming notice (emitResult={})", emitResult);
        }
    }
}
