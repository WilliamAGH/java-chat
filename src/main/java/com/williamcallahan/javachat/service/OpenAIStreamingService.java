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
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import com.williamcallahan.javachat.web.SseConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
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

    private static final int COMPLETE_REQUEST_TIMEOUT_SECONDS = 30;
    private static final String STREAM_STATUS_CODE_PROVIDER_FALLBACK =
            SseConstants.STATUS_CODE_STREAM_PROVIDER_FALLBACK;
    private static final String STREAM_STAGE_STREAM = SseConstants.STATUS_STAGE_STREAM;

    /** GitHub Models client when configured. */
    private OpenAIClient clientPrimary;

    /** OpenAI direct client when configured. */
    private OpenAIClient clientSecondary;

    private volatile boolean isAvailable;
    private final RateLimitService rateLimitService;
    private final OpenAiRequestFactory requestFactory;
    private final OpenAiProviderRoutingService providerRoutingService;

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
     * Creates a streaming service with explicit dependencies for routing and payload construction.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param requestFactory request payload and truncation builder
     * @param providerRoutingService provider ordering and fallback classifier
     */
    public OpenAIStreamingService(
            RateLimitService rateLimitService,
            OpenAiRequestFactory requestFactory,
            OpenAiProviderRoutingService providerRoutingService) {
        this.rateLimitService = rateLimitService;
        this.requestFactory = requestFactory;
        this.providerRoutingService = providerRoutingService;
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
                    "OpenAI streaming available (primaryConfigured={}, secondaryConfigured={})",
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
     * Streams a response using a structured prompt with provider failover before first token.
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
                        log.error("[LLM] {}", unavailableReason);
                        return Mono.<StreamingResult>error(new IllegalStateException(unavailableReason));
                    }

                    OpenAiProviderCandidate initialProvider = availableProviders.get(0);
                    Sinks.Many<StreamingNotice> noticeSink =
                            Sinks.many().multicast().onBackpressureBuffer();
                    StreamingAttemptContext attemptContext =
                            new StreamingAttemptContext(availableProviders, 0, noticeSink);
                    Flux<String> contentFlux = executeStreamingWithProviderFallback(
                                    structuredPrompt, temperature, attemptContext)
                            .doFinally(ignoredSignal -> noticeSink.tryEmitComplete());
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
        String truncatedPrompt = requestFactory.truncatePromptForCompletion(prompt);
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
                                requestFactory.buildCompletionRequest(truncatedPrompt, temperature, activeProvider);
                        try {
                            log.info("[LLM] Complete started (providerId={})", activeProvider.ordinal());
                            RequestOptions requestOptions = RequestOptions.builder()
                                    .timeout(completeTimeout())
                                    .build();
                            Response completion =
                                    providerCandidate.client().responses().create(requestParameters, requestOptions);
                            rateLimitService.recordSuccess(activeProvider);
                            log.debug("[LLM] Complete succeeded (providerId={})", activeProvider.ordinal());
                            return Mono.just(extractTextFromResponse(completion));
                        } catch (RuntimeException completionException) {
                            lastProviderFailure = completionException;
                            log.error(
                                    "[LLM] Complete failed (providerId={})",
                                    activeProvider.ordinal(),
                                    completionException);
                            providerRoutingService.recordProviderFailure(activeProvider, completionException);

                            boolean hasNextProvider = providerIndex + 1 < availableProviders.size();
                            if (hasNextProvider
                                    && providerRoutingService.isCompletionFallbackEligible(completionException)) {
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

    /**
     * Returns whether a streaming failure is likely recoverable with a retry.
     *
     * @param throwable streaming failure
     * @return true when fallback conditions indicate transient failure
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        return providerRoutingService.isRecoverableStreamingFailure(throwable);
    }

    private Flux<String> executeStreamingWithProviderFallback(
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
                .doOnNext(ignoredChunk -> emittedTextChunk.set(true))
                .onErrorResume(streamingFailure -> {
                    if (!attemptContext.hasNextProvider()
                            || emittedTextChunk.get()
                            || !providerRoutingService.isStreamingFallbackEligible(streamingFailure)) {
                        return Flux.error(streamingFailure);
                    }

                    StreamingAttemptContext nextAttempt = attemptContext.withNextProvider();
                    OpenAiProviderCandidate fallbackProvider = nextAttempt.currentProvider();
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
                    log.error("[LLM] Streaming failed (providerId={})", activeProvider.ordinal(), exception);
                    providerRoutingService.recordProviderFailure(activeProvider, exception);
                });
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

    private OpenAIClient createClient(String apiKey, String baseUrl) {
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                // Disable SDK-level retries: Reactor timeout and onErrorResume handle failures.
                // Retries cause InterruptedException when Reactor cancels a sleeping retry.
                .maxRetries(0)
                .build();
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

    private String normalizeBaseUrl(String baseUrl) {
        return OpenAiSdkUrlNormalizer.normalize(baseUrl);
    }

    private java.util.Optional<String> extractTextDelta(ResponseStreamEvent event) {
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
