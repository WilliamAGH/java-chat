package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.AsyncStreamResponse;
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
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.text.UnicodeVisibleContent;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Streams and completes chat responses using the configured OpenAI-compatible gateway.
 *
 * <p>This service orchestrates the OpenAI SDK transport and terminal failure
 * reporting while delegating admission and request construction to focused
 * collaborators.</p>
 */
@Service
public class OpenAIStreamingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingService.class);

    private static final String PROVIDER_UNAVAILABLE_MESSAGE =
            "LLM providers unavailable - active provider is rate limited or misconfigured";
    private static final Duration STREAM_OUTPUT_TIMEOUT = Duration.ofSeconds(20);
    /** OpenAI-compatible client when configured. */
    private OpenAIClient openAiClient;

    private volatile boolean isAvailable;
    private final RateLimitService rateLimitService;
    private final OpenAiRequestFactory requestFactory;
    private final OpenAiProviderRoutingService providerRoutingService;
    private final StreamingFailureReporter streamingFailureReporter;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:}")
    private String openaiBaseUrl;

    @Value("${OPENAI_STREAMING_REQUEST_TIMEOUT_SECONDS:90}")
    private long streamingRequestTimeoutSeconds;

    /** Sends live chat through the gateway's production tier; batch callers use {@code batch}. */
    @Value("${LLM_GATEWAY_TIER:production-z}")
    private String llmGatewayTier;

    /**
     * Creates a streaming service with explicit dependencies for routing and payload construction.
     *
     * @param rateLimitService provider rate-limit state tracker
     * @param requestFactory request payload and truncation builder
     * @param providerRoutingService configured-provider selection and failure classifier
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

    /** Initializes the shared-gateway OpenAI-compatible client. */
    @PostConstruct
    public void initializeClient() {
        initializeOpenAiClient();
        this.isAvailable = providerRoutingService.hasConfiguredProviderClient(openAiClient);
        if (!this.isAvailable) {
            log.warn(
                    "Configured chat provider has no matching API credential - OpenAI streaming will not be available");
        } else {
            log.info("OpenAI streaming available (openAiCompatibleConfigured={})", openAiClient != null);
        }
    }

    private void initializeOpenAiClient() {
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            if (openaiBaseUrl == null || openaiBaseUrl.isBlank()) {
                throw new IllegalStateException(
                        "OPENAI_BASE_URL must identify the shared LLM gateway when OPENAI_API_KEY is configured");
            }
            log.info("Initializing OpenAI-compatible client with the shared LLM gateway");
            this.openAiClient = createClient(openaiApiKey, openaiBaseUrl);
            log.info("OpenAI-compatible shared-gateway client initialized successfully");
        }
    }

    /** Closes OpenAI clients during application shutdown. */
    @PreDestroy
    public void shutdown() {
        closeClientSafely(openAiClient, RateLimitService.ApiProvider.OPENAI.getName());
    }

    /**
     * Streams a response from the configured provider.
     *
     * @param structuredPrompt typed prompt segments
     * @param temperature response temperature
     * @return stream result including text chunks and the selected provider
     */
    public Mono<StreamingResult> streamResponse(StructuredPrompt structuredPrompt, double temperature) {
        log.debug("Starting OpenAI stream with structured prompt");

        return Mono.<StreamingResult>defer(() -> {
                    if (!providerRoutingService.hasConfiguredProviderClient(openAiClient)) {
                        log.warn("[LLM] {}", PROVIDER_UNAVAILABLE_MESSAGE);
                        return Mono.<StreamingResult>error(new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
                    }

                    RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
                    OpenAiPreparedRequest preparedStreamingRequest =
                            requestFactory.prepareStreamingRequest(structuredPrompt, temperature);
                    Flux<String> responseTextChunks =
                            executeStreamingWithConfiguredProvider(preparedStreamingRequest, configuredProvider);
                    List<String> contextDocumentIds =
                            preparedStreamingRequest.structuredPrompt().contextDocuments().stream()
                                    .map(ContextDocumentSegment::documentId)
                                    .toList();
                    return Mono.just(new StreamingResult(responseTextChunks, configuredProvider, contextDocumentIds));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sends a non-streaming completion request to the configured provider.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @return completion text from the selected provider
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
     * @return completion text from the selected provider
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
     * @return completion text from the selected provider honoring the JSON contract
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
     * @return completion text from the selected provider honoring the JSON contract
     */
    public Mono<String> completeJsonObject(
            String prompt, double temperature, int maximumOutputTokens, Duration requestTimeout) {
        return Mono.defer(() -> executeCompletion(
                prompt, temperature, CompletionRequestConfiguration.jsonObject(maximumOutputTokens, requestTimeout)));
    }

    private Mono<String> executeCompletion(
            String prompt, double temperature, CompletionRequestConfiguration configuration) {
        return Mono.defer(() -> {
            RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
            ResponseCreateParams requestParameters = buildCompletionRequest(prompt, temperature, configuration);
            RequestOptions requestOptions = RequestOptions.builder()
                    .timeout(completeTimeout(configuration.requestTimeout()))
                    .build();
            OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();

            log.info("[LLM] Complete started (providerId={})", configuredProvider.ordinal());
            CompletableFuture<String> completionFuture = providerAdmission
                    .client()
                    .async()
                    .responses()
                    .create(requestParameters, requestOptions)
                    .thenApply(this::extractTextFromResponse);
            CompletableFuture<String> accountedCompletionFuture =
                    completionFuture.whenComplete((completionText, completionFailure) -> {
                        if (completionFailure == null) {
                            providerRoutingService.recordProviderSuccess(configuredProvider);
                            log.debug("[LLM] Complete succeeded (providerId={})", configuredProvider.ordinal());
                            return;
                        }
                        Throwable upstreamFailure = unwrapCompletionFailure(completionFailure);
                        recordProviderFailurePreservingUpstream(configuredProvider, upstreamFailure);
                        log.error(
                                "[LLM] Complete failed (providerId={})", configuredProvider.ordinal(), upstreamFailure);
                    });
            // The SDK returns dependent futures whose cancellation does not reach the underlying
            // OkHttp call. Let the request-owned SDK timeout finish parsing and close its response.
            return Mono.fromFuture(accountedCompletionFuture, true);
        });
    }

    private ResponseCreateParams buildCompletionRequest(
            String prompt, double temperature, CompletionRequestConfiguration configuration) {
        if (configuration.requireJsonObject()) {
            return requestFactory.buildJsonCompletionRequest(
                    prompt, temperature, configuration.maximumOutputTokens().orElseThrow());
        }
        if (configuration.maximumOutputTokens().isEmpty()) {
            return requestFactory.buildCompletionRequest(prompt, temperature);
        }
        return requestFactory.buildCompletionRequest(
                prompt, temperature, configuration.maximumOutputTokens().orElseThrow());
    }

    /**
     * Returns whether a streaming failure is transient at the request boundary.
     *
     * @param throwable streaming failure
     * @return true when the failure category is transient
     */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        Throwable upstreamFailure = ReportedStreamingFailure.findInCauseChain(throwable)
                .map(ReportedStreamingFailure::upstreamFailure)
                .orElse(throwable);
        return providerRoutingService.isRecoverableStreamingFailure(upstreamFailure);
    }

    private Flux<String> executeStreamingWithConfiguredProvider(
            OpenAiPreparedRequest preparedStreamingRequest, RateLimitService.ApiProvider configuredProvider) {
        log.info(
                "[LLM] Streaming started (structured, providerId={}, model={})",
                configuredProvider.ordinal(),
                preparedStreamingRequest.modelId());

        return Flux.defer(() -> {
            OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();
            AtomicBoolean emittedVisibleText = new AtomicBoolean(false);
            return executeStreamingRequest(
                            providerAdmission.client(), preparedStreamingRequest.responseParams(), configuredProvider)
                    .doOnNext(textChunk -> {
                        if (UnicodeVisibleContent.hasVisibleContent(textChunk)) {
                            emittedVisibleText.set(true);
                        }
                    })
                    .onErrorResume(streamingFailure -> {
                        return Flux.error(streamingFailureReporter.reportTerminalFailure(
                                streamingFailure,
                                new StreamingFailureReporter.TerminalAttempt(
                                        configuredProvider.getName(),
                                        preparedStreamingRequest.modelId(),
                                        1,
                                        1,
                                        emittedVisibleText.get())));
                    });
        });
    }

    private Flux<String> executeStreamingRequest(
            OpenAIClient client, ResponseCreateParams requestParameters, RateLimitService.ApiProvider activeProvider) {
        RequestOptions requestOptions =
                RequestOptions.builder().timeout(streamingTimeout()).build();

        return Flux.defer(() -> {
            AtomicBoolean emittedVisibleText = new AtomicBoolean(false);
            AtomicBoolean observedCompletedEvent = new AtomicBoolean(false);
            Flux<String> responseTextChunks = asyncResponseEvents(client, requestParameters, requestOptions)
                    .concatMap(responseStreamEvent -> {
                        if (responseStreamEvent.completed().isPresent()) {
                            observedCompletedEvent.set(true);
                        }
                        return extractTextOrTerminalFailure(responseStreamEvent);
                    });
            return enforceVisibleOutputDeadline(responseTextChunks)
                    .doOnNext(textChunk -> {
                        if (UnicodeVisibleContent.hasVisibleContent(textChunk)) {
                            emittedVisibleText.set(true);
                        }
                    })
                    .concatWith(Mono.defer(() -> {
                        if (!observedCompletedEvent.get()) {
                            return Mono.error(OpenAiResponseStreamException.missingCompletion());
                        }
                        if (!emittedVisibleText.get()) {
                            return Mono.error(OpenAiResponseStreamException.withoutVisibleText());
                        }
                        return Mono.empty();
                    }))
                    .doOnComplete(() -> {
                        log.debug("[LLM] Stream completed successfully (providerId={})", activeProvider.ordinal());
                        providerRoutingService.recordProviderSuccess(activeProvider);
                    })
                    .doOnError(exception -> {
                        recordProviderFailurePreservingUpstream(activeProvider, exception);
                    });
        });
    }

    Flux<String> enforceVisibleOutputDeadline(Flux<String> responseTextChunks) {
        return responseTextChunks.publish(sharedTextChunks -> {
            Flux<String> visibleOutputWatchdog = sharedTextChunks
                    .filter(UnicodeVisibleContent::hasVisibleContent)
                    .timeout(STREAM_OUTPUT_TIMEOUT)
                    .thenMany(Flux.empty());
            return Flux.merge(sharedTextChunks, visibleOutputWatchdog);
        });
    }

    private Flux<ResponseStreamEvent> asyncResponseEvents(
            OpenAIClient client, ResponseCreateParams requestParameters, RequestOptions requestOptions) {
        return Flux.defer(() -> {
            AsyncStreamResponse<ResponseStreamEvent> responseStream =
                    client.async().responses().createStreaming(requestParameters, requestOptions);
            AtomicBoolean responseStreamClosed = new AtomicBoolean();
            Runnable closeResponseStream = () -> {
                if (responseStreamClosed.compareAndSet(false, true)) {
                    responseStream.close();
                }
            };
            return Flux.<ResponseStreamEvent>create(
                            responseEventSink -> {
                                responseEventSink.onCancel(closeResponseStream::run);
                                try {
                                    responseStream.subscribe(new AsyncStreamResponse.Handler<>() {
                                        @Override
                                        public void onNext(ResponseStreamEvent responseStreamEvent) {
                                            if (!responseEventSink.isCancelled()) {
                                                responseEventSink.next(responseStreamEvent);
                                            }
                                        }

                                        @Override
                                        public void onComplete(Optional<Throwable> streamingFailure) {
                                            if (responseEventSink.isCancelled()) {
                                                return;
                                            }
                                            streamingFailure
                                                    .map(OpenAIStreamingService::unwrapCompletionFailure)
                                                    .ifPresentOrElse(
                                                            responseEventSink::error, responseEventSink::complete);
                                        }
                                    });
                                } catch (RuntimeException subscriptionFailure) {
                                    closeResponseStream.run();
                                    responseEventSink.error(subscriptionFailure);
                                }
                            },
                            FluxSink.OverflowStrategy.ERROR)
                    .doOnError(streamFailure -> {
                        if (Exceptions.isOverflow(streamFailure)) {
                            closeResponseStream.run();
                        }
                    });
        });
    }

    private void recordProviderFailurePreservingUpstream(
            RateLimitService.ApiProvider provider, Throwable upstreamFailure) {
        try {
            providerRoutingService.recordProviderFailure(provider, upstreamFailure);
        } catch (RuntimeException providerAccountingFailure) {
            upstreamFailure.addSuppressed(providerAccountingFailure);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable completionFailure) {
        if (completionFailure instanceof CompletionException && completionFailure.getCause() != null) {
            return completionFailure.getCause();
        }
        return completionFailure;
    }

    private Timeout streamingTimeout() {
        return Timeout.builder()
                .request(Duration.ofSeconds(Math.max(1, streamingRequestTimeoutSeconds)))
                .build();
    }

    private Timeout completeTimeout(Duration requestTimeout) {
        return Timeout.builder().request(requestTimeout).build();
    }

    private OpenAIClient createClient(String apiKey, String baseUrl) {
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(OpenAiSdkUrlNormalizer.normalize(baseUrl))
                // Caller-owned request timeouts and provider routing own failure handling.
                // SDK retry sleeps interfere with reactive cancellation.
                .maxRetries(0)
                .putHeader(LlmGatewayTier.REQUEST_TIER_HEADER, resolvedLlmGatewayTier())
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

    private Mono<String> extractTextOrTerminalFailure(ResponseStreamEvent responseStreamEvent) {
        var errorEvent = responseStreamEvent.error();
        if (errorEvent.isPresent()) {
            return Mono.error(
                    OpenAiResponseStreamException.error(errorEvent.orElseThrow().code()));
        }
        var failedEvent = responseStreamEvent.failed();
        if (failedEvent.isPresent()) {
            var providerCode = failedEvent.orElseThrow().response().error().map(providerError -> providerError
                    .code()
                    .asString());
            return Mono.error(OpenAiResponseStreamException.failed(providerCode));
        }
        var incompleteEvent = responseStreamEvent.incomplete();
        if (incompleteEvent.isPresent()) {
            var incompleteReason = incompleteEvent
                    .orElseThrow()
                    .response()
                    .incompleteDetails()
                    .flatMap(Response.IncompleteDetails::reason)
                    .map(Response.IncompleteDetails.Reason::asString);
            return Mono.error(OpenAiResponseStreamException.incomplete(incompleteReason));
        }
        return Mono.justOrEmpty(responseStreamEvent
                .outputTextDelta()
                .map(ResponseTextDeltaEvent::delta)
                .or(() -> responseStreamEvent.refusalDelta().map(refusalDeltaEvent -> refusalDeltaEvent.delta())));
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
     * @return true when the configured provider client is initialized
     */
    public boolean isAvailable() {
        return isAvailable && providerRoutingService.hasConfiguredProviderClient(openAiClient);
    }

    /**
     * Returns whether chat work can begin without entering a known provider outage window.
     *
     * @return true when the configured provider is initialized and currently eligible
     */
    public boolean canAttemptRequest() {
        return isAvailable && providerRoutingService.canAttemptConfiguredProviderRequest(openAiClient);
    }

    private OpenAiProviderCandidate requireConfiguredProviderAdmission() {
        return providerRoutingService
                .admitConfiguredProviderRequest(openAiClient)
                .orElseThrow(() -> new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
    }
}
