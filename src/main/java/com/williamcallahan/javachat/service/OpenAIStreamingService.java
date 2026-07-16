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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Streams and completes chat responses using OpenAI-compatible providers.
 *
 * <p>This service orchestrates single-provider SDK transport and terminal failure
 * reporting while delegating provider selection and request construction to focused
 * collaborators.</p>
 */
@Service
public class OpenAIStreamingService {
    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamingService.class);

    private static final String PROVIDER_UNAVAILABLE_MESSAGE =
            "LLM providers unavailable - active provider is rate limited or misconfigured";

    /** GitHub Models client when configured. */
    private OpenAIClient githubModelsClient;

    /** OpenAI-compatible client when configured. */
    private OpenAIClient openAiClient;

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

    /**
     * Initializes OpenAI-compatible clients for configured providers after Spring injects credentials.
     */
    @PostConstruct
    public void initializeClient() {
        RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
        if (configuredProvider == RateLimitService.ApiProvider.GITHUB_MODELS) {
            initializeGithubModelsClient();
        } else if (configuredProvider == RateLimitService.ApiProvider.OPENAI) {
            initializeOpenAiClient();
        }

        this.isAvailable = providerRoutingService.hasConfiguredProviderClient(githubModelsClient, openAiClient);
        if (!this.isAvailable) {
            log.warn(
                    "Configured chat provider has no matching API credential - OpenAI streaming will not be available");
        } else {
            log.info(
                    "OpenAI streaming available (githubModelsConfigured={}, openAiCompatibleConfigured={})",
                    githubModelsClient != null,
                    openAiClient != null);
        }
    }

    private void initializeGithubModelsClient() {
        if (githubToken != null && !githubToken.isBlank()) {
            log.info("Initializing OpenAI client with GitHub Models endpoint");
            this.githubModelsClient = createClient(githubToken, githubModelsBaseUrl);
            log.info("OpenAI client initialized successfully with GitHub Models");
        }
    }

    private void initializeOpenAiClient() {
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            log.info("Initializing OpenAI client with OpenAI API");
            this.openAiClient = createClient(openaiApiKey, openaiBaseUrl);
            log.info("OpenAI client initialized successfully with OpenAI API");
        }
    }

    /** Closes OpenAI clients during application shutdown. */
    @PreDestroy
    public void shutdown() {
        closeClientSafely(githubModelsClient, RateLimitService.ApiProvider.GITHUB_MODELS.getName());
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
                    if (!providerRoutingService.hasConfiguredProviderClient(githubModelsClient, openAiClient)) {
                        log.warn("[LLM] {}", PROVIDER_UNAVAILABLE_MESSAGE);
                        return Mono.<StreamingResult>error(new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
                    }

                    RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
                    Flux<String> responseTextChunks =
                            executeStreamingWithConfiguredProvider(structuredPrompt, temperature, configuredProvider);
                    return Mono.just(new StreamingResult(responseTextChunks, configuredProvider));
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
        return Mono.<String>defer(() -> {
                    RateLimitService.ApiProvider configuredProvider = providerRoutingService.configuredProvider();
                    ResponseCreateParams requestParameters =
                            buildCompletionRequest(prompt, temperature, configuredProvider, configuration);
                    RequestOptions requestOptions = RequestOptions.builder()
                            .timeout(completeTimeout(configuration.requestTimeout()))
                            .build();
                    OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();

                    try {
                        log.info("[LLM] Complete started (providerId={})", configuredProvider.ordinal());
                        Response completion =
                                providerAdmission.client().responses().create(requestParameters, requestOptions);
                        rateLimitService.recordSuccess(configuredProvider);
                        log.debug("[LLM] Complete succeeded (providerId={})", configuredProvider.ordinal());
                        return Mono.just(extractTextFromResponse(completion));
                    } catch (RuntimeException completionException) {
                        providerRoutingService.recordProviderFailure(configuredProvider, completionException);
                        log.error(
                                "[LLM] Complete failed (providerId={})",
                                configuredProvider.ordinal(),
                                completionException);
                        return Mono.error(completionException);
                    }
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
            StructuredPrompt structuredPrompt, double temperature, RateLimitService.ApiProvider configuredProvider) {
        OpenAiPreparedRequest preparedStreamingRequest =
                requestFactory.prepareStreamingRequest(structuredPrompt, temperature, configuredProvider);

        log.info(
                "[LLM] Streaming started (structured, providerId={}, model={})",
                configuredProvider.ordinal(),
                preparedStreamingRequest.modelId());

        return Flux.defer(() -> {
            OpenAiProviderCandidate providerAdmission = requireConfiguredProviderAdmission();
            AtomicBoolean emittedTextChunk = new AtomicBoolean(false);
            return executeStreamingRequest(
                            providerAdmission.client(), preparedStreamingRequest.responseParams(), configuredProvider)
                    .doOnNext(textChunk -> {
                        if (!textChunk.isEmpty()) {
                            emittedTextChunk.set(true);
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
                                        emittedTextChunk.get())));
                    });
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
     * @return true when the configured provider client is initialized
     */
    public boolean isAvailable() {
        return isAvailable && providerRoutingService.hasConfiguredProviderClient(githubModelsClient, openAiClient);
    }

    private OpenAiProviderCandidate requireConfiguredProviderAdmission() {
        return providerRoutingService
                .admitConfiguredProviderRequest(githubModelsClient, openAiClient)
                .orElseThrow(() -> new IllegalStateException(PROVIDER_UNAVAILABLE_MESSAGE));
    }
}
