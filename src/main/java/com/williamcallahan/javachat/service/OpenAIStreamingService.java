package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.williamcallahan.javachat.application.completion.CompletionRequestConfiguration;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import com.williamcallahan.javachat.domain.prompt.CompletionPrompt;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.text.UnicodeVisibleContent;
import com.williamcallahan.javachat.support.OpenAiSdkUrlNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
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

/** Owns shared-gateway SDK transport and terminal failure reporting. */
@Service
public class OpenAIStreamingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingService.class);

    private static final String PROVIDER_UNAVAILABLE_MESSAGE =
            "LLM providers unavailable - active provider is rate limited or misconfigured";
    /** OpenAI-compatible client when configured. */
    private OpenAIClient openAiClient;

    private volatile boolean isAvailable;
    private final MeterRegistry meterRegistry;
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

    /** Keeps provider admission, request construction, and failure reporting independently owned. */
    public OpenAIStreamingService(
            MeterRegistry meterRegistry,
            OpenAiRequestFactory requestFactory,
            OpenAiProviderRoutingService providerRoutingService,
            StreamingFailureReporter streamingFailureReporter) {
        this.meterRegistry = meterRegistry;
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

    /** Retains selected-provider and context-document provenance alongside the response stream. */
    public Mono<StreamingResult> streamResponse(
            LlmGatewayTier.Purpose purpose, StructuredPrompt structuredPrompt, double temperature) {
        java.util.Objects.requireNonNull(purpose, "purpose");
        log.debug("Starting OpenAI stream with structured prompt");

        return Mono.<StreamingResult>defer(() -> {
                    if (!providerRoutingService.hasConfiguredProviderClient(openAiClient)) {
                        log.warn("[LLM] {}", PROVIDER_UNAVAILABLE_MESSAGE);
                        return Mono.<StreamingResult>error(new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
                    }

                    RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
                    OpenAiPreparedRequest preparedStreamingRequest =
                            requestFactory.prepareStreamingRequest(purpose, structuredPrompt, temperature);
                    Flux<String> responseTextChunks = executeStreamingWithConfiguredProvider(
                            preparedStreamingRequest, configuredProvider, purpose);
                    List<String> contextDocumentIds =
                            preparedStreamingRequest.structuredPrompt().contextDocuments().stream()
                                    .map(ContextDocumentSegment::documentId)
                                    .toList();
                    return Mono.just(new StreamingResult(responseTextChunks, configuredProvider, contextDocumentIds));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Uses the configured output budget for a non-streaming text completion. */
    public Mono<String> complete(LlmGatewayTier.Purpose purpose, String prompt, double temperature) {
        return executeCompletion(
                purpose, new CompletionPrompt("", prompt), temperature, CompletionRequestConfiguration.defaultText());
    }

    /** Preserves the caller's output budget for a non-streaming text completion. */
    public Mono<String> complete(
            LlmGatewayTier.Purpose purpose, String prompt, double temperature, int maximumOutputTokens) {
        return Mono.defer(() -> executeCompletion(
                purpose,
                new CompletionPrompt("", prompt),
                temperature,
                CompletionRequestConfiguration.boundedText(maximumOutputTokens)));
    }

    /** Requires a JSON object within the caller's output budget and the default request timeout. */
    public Mono<String> completeJsonObject(
            LlmGatewayTier.Purpose purpose, CompletionPrompt prompt, double temperature, int maximumOutputTokens) {
        return completeJsonObject(
                purpose,
                prompt,
                temperature,
                maximumOutputTokens,
                CompletionRequestConfiguration.defaultRequestTimeout());
    }

    /** Requires a JSON object within both the caller's output budget and whole-request timeout. */
    public Mono<String> completeJsonObject(
            LlmGatewayTier.Purpose purpose,
            CompletionPrompt prompt,
            double temperature,
            int maximumOutputTokens,
            Duration requestTimeout) {
        return Mono.defer(() -> executeCompletion(
                purpose,
                prompt,
                temperature,
                CompletionRequestConfiguration.jsonObject(maximumOutputTokens, requestTimeout)));
    }

    private Mono<String> executeCompletion(
            LlmGatewayTier.Purpose purpose,
            CompletionPrompt prompt,
            double temperature,
            CompletionRequestConfiguration configuration) {
        java.util.Objects.requireNonNull(purpose, "purpose");
        return Mono.defer(() -> {
            RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
            ResponseCreateParams requestParameters =
                    buildCompletionRequest(prompt, temperature, configuration).toBuilder()
                            .promptCacheKey(requestFactory.promptCacheKey(purpose, prompt.instructions()))
                            .putAdditionalHeader(
                                    LlmGatewayTier.REQUEST_WORKLOAD_HEADER,
                                    workloadTier().workloadHeader(purpose))
                            .build();
            RequestOptions requestOptions = RequestOptions.builder()
                    .timeout(Timeout.builder()
                            .request(configuration.requestTimeout())
                            .build())
                    .build();
            OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();

            log.info("[LLM] Complete started (providerId={})", configuredProvider.ordinal());
            CompletableFuture<String> completionFuture = providerAdmission
                    .client()
                    .async()
                    .responses()
                    .create(requestParameters, requestOptions)
                    .thenApply(providerResponse -> {
                        recordUsage(providerResponse, purpose);
                        return extractTextFromCompletedResponse(providerResponse);
                    });
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
            CompletionPrompt prompt, double temperature, CompletionRequestConfiguration configuration) {
        if (configuration.requireJsonObject()) {
            return requestFactory.buildJsonCompletionRequest(
                    prompt, temperature, configuration.maximumOutputTokens().orElseThrow());
        }
        if (configuration.maximumOutputTokens().isEmpty()) {
            return requestFactory.buildCompletionRequest(prompt.input(), temperature);
        }
        return requestFactory.buildCompletionRequest(
                prompt.input(), temperature, configuration.maximumOutputTokens().orElseThrow());
    }

    /** Classifies the upstream failure even when the reporting boundary has wrapped it. */
    public boolean isRecoverableStreamingFailure(Throwable throwable) {
        Throwable upstreamFailure = ReportedStreamingFailure.findInCauseChain(throwable)
                .map(ReportedStreamingFailure::upstreamFailure)
                .orElse(throwable);
        return providerRoutingService.isRecoverableStreamingFailure(upstreamFailure);
    }

    private Flux<String> executeStreamingWithConfiguredProvider(
            OpenAiPreparedRequest preparedStreamingRequest,
            RateLimitService.ApiProvider configuredProvider,
            LlmGatewayTier.Purpose purpose) {
        log.info(
                "[LLM] Streaming started (structured, providerId={}, model={})",
                configuredProvider.ordinal(),
                preparedStreamingRequest.modelId());

        return Flux.defer(() -> {
            OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();
            AtomicBoolean emittedVisibleText = new AtomicBoolean(false);
            return executeStreamingRequest(
                            providerAdmission.client(),
                            preparedStreamingRequest.responseParams().toBuilder()
                                    .putAdditionalHeader(
                                            LlmGatewayTier.REQUEST_WORKLOAD_HEADER,
                                            workloadTier().workloadHeader(purpose))
                                    .build(),
                            configuredProvider,
                            purpose)
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
            OpenAIClient client,
            ResponseCreateParams requestParameters,
            RateLimitService.ApiProvider activeProvider,
            LlmGatewayTier.Purpose purpose) {
        RequestOptions requestOptions = RequestOptions.builder()
                .timeout(Timeout.builder().request(streamingRequestTimeout()).build())
                .build();

        return Flux.defer(() -> {
            AtomicBoolean emittedVisibleText = new AtomicBoolean(false);
            AtomicBoolean observedCompletedEvent = new AtomicBoolean(false);
            AtomicBoolean recordedTerminalUsage = new AtomicBoolean(false);
            Flux<String> responseTextChunks = asyncResponseEvents(client, requestParameters, requestOptions)
                    .concatMap(responseStreamEvent -> {
                        if (responseStreamEvent.completed().isPresent()) {
                            observedCompletedEvent.set(true);
                        }
                        responseStreamEvent
                                .completed()
                                .map(ResponseCompletedEvent::response)
                                .or(() -> responseStreamEvent.failed().map(ResponseFailedEvent::response))
                                .or(() -> responseStreamEvent.incomplete().map(ResponseIncompleteEvent::response))
                                .ifPresent(terminalResponse -> {
                                    if (recordedTerminalUsage.compareAndSet(false, true)) {
                                        recordUsage(terminalResponse, purpose);
                                    }
                                });
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
                            return Mono.error(OpenAiResponseException.missingCompletion());
                        }
                        if (!emittedVisibleText.get()) {
                            return Mono.error(OpenAiResponseException.withoutVisibleText());
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

    /** Gives reasoning the full request budget while rejecting streams that never yield visible text. */
    Flux<String> enforceVisibleOutputDeadline(Flux<String> responseTextChunks) {
        return responseTextChunks.publish(sharedTextChunks -> {
            Flux<String> visibleOutputWatchdog = sharedTextChunks
                    .filter(UnicodeVisibleContent::hasVisibleContent)
                    .next()
                    .timeout(streamingRequestTimeout())
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

    /**
     * Resolves the whole-request streaming budget shared by the SDK request timeout and the
     * visible-output watchdog, so the watchdog can never be tighter than the request budget.
     */
    private Duration streamingRequestTimeout() {
        return Duration.ofSeconds(Math.max(1, streamingRequestTimeoutSeconds));
    }

    private OpenAIClient createClient(String apiKey, String baseUrl) {
        String requestTier = resolvedLlmGatewayTier();
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(OpenAiSdkUrlNormalizer.normalize(baseUrl))
                // Caller-owned request timeouts and provider routing own failure handling.
                // SDK retry sleeps interfere with reactive cancellation.
                .maxRetries(0)
                .putHeader(LlmGatewayTier.REQUEST_TIER_HEADER, requestTier)
                .build();
    }

    private LlmGatewayTier workloadTier() {
        return LlmGatewayTier.BATCH.requestHeader().equals(resolvedLlmGatewayTier())
                ? LlmGatewayTier.BATCH
                : LlmGatewayTier.LIVE;
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
            Optional<ResponseError.Code> providerCode =
                    errorEvent.orElseThrow().code().map(ResponseError.Code::of);
            return Mono.error(OpenAiResponseException.error(providerCode));
        }
        var failedEvent = responseStreamEvent.failed();
        if (failedEvent.isPresent()) {
            Optional<ResponseError.Code> providerCode =
                    failedEvent.orElseThrow().response().error().map(ResponseError::code);
            return Mono.error(OpenAiResponseException.failed(providerCode));
        }
        var incompleteEvent = responseStreamEvent.incomplete();
        if (incompleteEvent.isPresent()) {
            Optional<Response.IncompleteDetails.Reason> incompleteReason = incompleteEvent
                    .orElseThrow()
                    .response()
                    .incompleteDetails()
                    .flatMap(Response.IncompleteDetails::reason);
            return Mono.error(OpenAiResponseException.incomplete(incompleteReason));
        }
        return Mono.justOrEmpty(responseStreamEvent
                .outputTextDelta()
                .map(ResponseTextDeltaEvent::delta)
                .or(() -> responseStreamEvent.refusalDelta().map(refusalDeltaEvent -> refusalDeltaEvent.delta())));
    }

    private void recordUsage(Response providerResponse, LlmGatewayTier.Purpose purpose) {
        if (providerResponse == null) {
            return;
        }
        // Gateways may omit usage detail fields; the SDK's required accessors would throw and fail
        // a completed call, so only reported counts are recorded.
        providerResponse.usage().ifPresent(usage -> {
            String workload = purpose.name();
            usage._inputTokens().asKnown().ifPresent(count -> countTokens(workload, "input", count));
            usage._inputTokensDetails().asKnown().ifPresent(details -> {
                details._cachedTokens().asKnown().ifPresent(count -> countTokens(workload, "cached_input", count));
                details._cacheWriteTokens().asKnown().ifPresent(count -> countTokens(workload, "cache_write", count));
            });
            usage._outputTokens().asKnown().ifPresent(count -> countTokens(workload, "output", count));
        });
    }

    private void countTokens(String workload, String tokenType, long count) {
        meterRegistry
                .counter("llm.tokens", "purpose", workload, "type", tokenType)
                .increment(count);
    }

    private String extractTextFromCompletedResponse(Response providerResponse) {
        if (providerResponse == null) {
            throw OpenAiResponseException.missingCompletion();
        }
        ResponseStatus responseStatus =
                providerResponse.status().orElseThrow(OpenAiResponseException::missingCompletion);
        if (ResponseStatus.FAILED.equals(responseStatus)) {
            Optional<ResponseError.Code> providerCode = providerResponse.error().map(ResponseError::code);
            throw OpenAiResponseException.failed(providerCode);
        }
        if (ResponseStatus.INCOMPLETE.equals(responseStatus)) {
            Optional<Response.IncompleteDetails.Reason> incompleteReason =
                    providerResponse.incompleteDetails().flatMap(Response.IncompleteDetails::reason);
            throw OpenAiResponseException.incomplete(incompleteReason);
        }
        if (ResponseStatus.CANCELLED.equals(responseStatus)) {
            throw OpenAiResponseException.cancelled();
        }
        if (!ResponseStatus.COMPLETED.equals(responseStatus)) {
            throw OpenAiResponseException.missingCompletion();
        }
        StringBuilder outputBuilder = new StringBuilder();
        for (ResponseOutputItem providerOutput : providerResponse.output()) {
            if (!providerOutput.isMessage()) {
                continue;
            }
            ResponseOutputMessage message = providerOutput.asMessage();
            for (ResponseOutputMessage.Content messageContent : message.content()) {
                if (messageContent.isOutputText()) {
                    ResponseOutputText outputText = messageContent.asOutputText();
                    outputBuilder.append(outputText.text());
                } else if (messageContent.isRefusal()) {
                    outputBuilder.append(messageContent.asRefusal().refusal());
                }
            }
        }
        String visibleCompletion = outputBuilder.toString();
        if (!UnicodeVisibleContent.hasVisibleContent(visibleCompletion)) {
            throw OpenAiResponseException.withoutVisibleText();
        }
        return visibleCompletion;
    }

    /** Requires both successful initialization and an available configured-provider client. */
    public boolean isAvailable() {
        return isAvailable && providerRoutingService.hasConfiguredProviderClient(openAiClient);
    }

    /** Rejects chat admission during a known outage of the configured provider. */
    public boolean canAttemptRequest() {
        return isAvailable && providerRoutingService.canAttemptConfiguredProviderRequest(openAiClient);
    }

    private OpenAiProviderCandidate requireConfiguredProviderAdmission() {
        return providerRoutingService
                .admitConfiguredProviderRequest(openAiClient)
                .orElseThrow(() -> new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
    }
}
