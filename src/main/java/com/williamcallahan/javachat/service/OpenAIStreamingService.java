package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ChatModel;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.errors.RateLimitException;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
    private static final String USER_MARKER = "User:";
    private static final String CONTEXT_MARKER = "[CTX ";
    private static final String TRUNCATION_NOTICE_GPT5 = "[Context truncated due to GPT-5 8K input limit]\n\n";
    private static final String TRUNCATION_NOTICE_GENERIC = "[Context truncated due to model input limit]\n\n";
    private static final String PARAGRAPH_SEPARATOR = "\n\n";
    private static final int TRUNCATION_SCAN_WINDOW = 2000;
    private static final int MAX_COMPLETION_TOKENS = 4000;
    private static final int COMPLETE_REQUEST_TIMEOUT_SECONDS = 30;
    private OpenAIClient clientPrimary;   // Prefer GitHub Models when available
    private OpenAIClient clientSecondary; // Fallback to OpenAI when available
    private volatile boolean isAvailable = false;
    private final RateLimitManager rateLimitManager;
    // When primary (GitHub Models) fails with rate limit/timeout/auth, temporarily avoid using it
    private volatile long primaryBackoffUntilEpochMs = 0L;
    
    /**
     * Creates a streaming service that can consult rate limit state when selecting an active provider.
     *
     * @param rateLimitManager rate limit state tracker
     */
    public OpenAIStreamingService(RateLimitManager rateLimitManager) {
        this.rateLimitManager = rateLimitManager;
    }

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;
    
    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;
    
    @Value("${OPENAI_BASE_URL:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${OPENAI_MODEL:gpt-5}")
    private String model;
    
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
                log.warn("No API credentials found (GITHUB_TOKEN or OPENAI_API_KEY) - OpenAI streaming will not be available");
            } else {
                log.info(
                    "OpenAI streaming available (model={}, primary={}, secondary={})",
                    model,
                    clientPrimary != null,
                    clientSecondary != null
                );
            }
        } catch (RuntimeException initializationException) {
            log.error("Failed to initialize OpenAI client", initializationException);
            this.isAvailable = false;
        }
    }

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
            log.debug("Closed OpenAI client ({})", clientName);
        } catch (RuntimeException closeException) {
            log.warn("Failed to close OpenAI client ({})", clientName, closeException);
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
     * Stream a response from the OpenAI API using clean, native streaming support.
     * 
     * @param prompt The complete prompt to send to the model
     * @param temperature The temperature setting for response generation
     * @return A Flux of content strings as they arrive from the model
     */
    public Flux<String> streamResponse(String prompt, double temperature) {
        log.debug("Starting OpenAI stream");
        
        return Flux.<String>defer(() -> {
            String truncatedPrompt = truncatePromptForModel(prompt);
            ChatCompletionCreateParams params = buildChatParams(truncatedPrompt, temperature);
            OpenAIClient streamingClient = selectClientForStreaming();
            
            if (streamingClient == null) {
                String error = "All LLM providers unavailable - check rate limits and API credentials";
                log.error("[LLM] {}", error);
                return Flux.<String>error(new IllegalStateException(error));
            }
            
            RateLimitManager.ApiProvider activeProvider = isPrimaryClient(streamingClient)
                    ? RateLimitManager.ApiProvider.GITHUB_MODELS
                    : RateLimitManager.ApiProvider.OPENAI;
            log.info("[LLM] [{}] Streaming started", activeProvider.getName());
            
            RequestOptions requestOptions = RequestOptions.builder()
                .timeout(streamingTimeout())
                .build();

            // StreamResponse implements AutoCloseable; explicit type params help inference
            return Flux.<String, StreamResponse<ChatCompletionChunk>>using(
                () -> streamingClient.chat().completions().createStreaming(params, requestOptions),
                (StreamResponse<ChatCompletionChunk> responseStream) -> Flux.fromStream(responseStream.stream())
                    .concatMap(chunk -> Flux.fromIterable(chunk.choices()))
                    .concatMap(choice -> Mono.justOrEmpty(choice.delta().content()))
            )
            .doOnComplete(() -> {
                log.debug("[LLM] [{}] Stream completed successfully", activeProvider.getName());
                if (rateLimitManager != null) {
                    rateLimitManager.recordSuccess(activeProvider);
                }
            })
            .doOnError(exception -> {
                log.error("[LLM] [{}] Streaming failed: {}",
                        activeProvider.getName(), exception.getMessage(), exception);
                if (rateLimitManager != null) {
                    recordProviderFailure(activeProvider, exception);
                }
                maybeBackoffPrimary(streamingClient, exception);
            });
        })
        // Move blocking SDK stream consumption off the servlet thread.
        // Prevents thread starvation and aligns with Reactor best practices.
        .subscribeOn(Schedulers.boundedElastic());
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
            RateLimitManager.ApiProvider activeProvider = isPrimaryClient(blockingClient)
                    ? RateLimitManager.ApiProvider.GITHUB_MODELS
                    : RateLimitManager.ApiProvider.OPENAI;
            ChatCompletionCreateParams params = buildChatParams(truncatedPrompt, temperature);
            try {
                log.info("[LLM] [{}] Complete started", activeProvider.getName());
                RequestOptions requestOptions = RequestOptions.builder()
                    .timeout(completeTimeout())
                    .build();
                ChatCompletion completion = blockingClient.chat().completions().create(params, requestOptions);
                if (rateLimitManager != null) {
                    rateLimitManager.recordSuccess(activeProvider);
                }
                log.debug("[LLM] [{}] Complete succeeded", activeProvider.getName());
                String response = completion.choices().stream()
                        .findFirst()
                        .flatMap(choice -> choice.message().content())
                        .orElse("");
                return Mono.just(response);
            } catch (RuntimeException completionException) {
                log.error("[LLM] [{}] Complete failed: {}",
                        activeProvider.getName(), completionException.getMessage(), completionException);
                if (rateLimitManager != null) {
                    recordProviderFailure(activeProvider, completionException);
                }
                maybeBackoffPrimary(blockingClient, completionException);
                return Mono.error(completionException);
            }
        }).subscribeOn(Schedulers.boundedElastic());
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

    private void recordProviderFailure(RateLimitManager.ApiProvider provider, Throwable throwable) {
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

    private ChatCompletionCreateParams buildChatParams(String prompt, double temperature) {
        String normalizedModelId = normalizedModelId();
        boolean gpt5Family = isGpt5Family(normalizedModelId);
        boolean reasoningModel = gpt5Family || normalizedModelId.startsWith("o");

        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(ChatModel.of(normalizedModelId));

        if (gpt5Family) {
            // GPT-5: omit temperature and set conservative max output tokens
            builder.maxCompletionTokens(MAX_COMPLETION_TOKENS);
            log.debug("Using GPT-5 configuration (no regression)");

            ReasoningEffort effort = resolveReasoningEffort(normalizedModelId);
            if (effort != null) {
                builder.reasoningEffort(effort);
            }
        } else if (!reasoningModel && Double.isFinite(temperature)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }
    
    // Model mapping removed to prevent unintended regression; use configured model id
    
    /**
     * Truncate prompt conservatively based on model limits to avoid 413 errors.
     */
    private String truncatePromptForModel(String prompt) {
        if (prompt == null || prompt.isEmpty()) return prompt;
        // Approximate safe character budgets (chars ~ tokens * ~4)
        final int MAX_CHARS_GPT5_INPUT = 28_000; // ~7k tokens, under 8k input limit
        final int MAX_CHARS_DEFAULT = 400_000;   // generous for high-context models

        String modelId = normalizedModelId();
        int limit = (isGpt5Family(modelId) || modelId.startsWith("o")) // Conservative limit for reasoning models
            ? MAX_CHARS_GPT5_INPUT
            : MAX_CHARS_DEFAULT;

        if (prompt.length() <= limit) return prompt;

        // Prefer keeping the most recent context and user message
        int lastUserMarkerIndex = prompt.lastIndexOf(USER_MARKER);
        if (lastUserMarkerIndex > 0 && lastUserMarkerIndex > prompt.length() - TRUNCATION_SCAN_WINDOW) {
            String recentContext = prompt.substring(Math.max(0, prompt.length() - limit));
            // Trim to a clean-ish boundary
            int paragraphBoundaryIndex = recentContext.indexOf(PARAGRAPH_SEPARATOR);
            if (paragraphBoundaryIndex > 0 && paragraphBoundaryIndex < TRUNCATION_SCAN_WINDOW) {
                recentContext = recentContext.substring(paragraphBoundaryIndex + PARAGRAPH_SEPARATOR.length());
            }
            int contextMarkerIndex = recentContext.indexOf(CONTEXT_MARKER);
            if (contextMarkerIndex > 0 && contextMarkerIndex < TRUNCATION_SCAN_WINDOW) {
                recentContext = recentContext.substring(contextMarkerIndex);
            }
            return TRUNCATION_NOTICE_GPT5 + recentContext;
        }
        return TRUNCATION_NOTICE_GENERIC + prompt.substring(prompt.length() - limit);
    }

    private String normalizedModelId() {
        String rawModelId = model == null ? "" : model.trim();
        return rawModelId.isEmpty() ? "gpt-5" : AsciiTextNormalizer.toLowerAscii(rawModelId);
    }

    private boolean isGpt5Family(String modelId) {
        return modelId != null && modelId.startsWith("gpt-5");
    }

    private ReasoningEffort resolveReasoningEffort(String normalizedModelId) {
        if (reasoningEffortSetting == null || reasoningEffortSetting.isBlank()) {
            return null;
        }
        return ReasoningEffort.of(AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim()));
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String trimmed = baseUrl.trim();
        String normalized = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (normalized.endsWith("/inference")) {
            // openai-java expects baseUrl to include the API version prefix, e.g. /v1.
            return normalized + "/v1";
        }
        return normalized;
    }
    
    /**
     * Check if the OpenAI streaming service is properly configured and available.
     */
    public boolean isAvailable() {
        return isAvailable && (clientPrimary != null || clientSecondary != null);
    }

    private OpenAIClient selectClientForStreaming() {
        // Prefer OpenAI when available (more reliable)
        if (rateLimitManager != null && clientSecondary != null
                && rateLimitManager.isProviderAvailable(RateLimitManager.ApiProvider.OPENAI)) {
            return clientSecondary;
        }

        // Try GitHub Models if not in backoff and not rate limited
        boolean githubOk = clientPrimary != null && !isPrimaryInBackoff();
        if (rateLimitManager != null && clientPrimary != null) {
            githubOk = githubOk && rateLimitManager.isProviderAvailable(RateLimitManager.ApiProvider.GITHUB_MODELS);
        }
        if (githubOk) {
            return clientPrimary;
        }

        // Fall back to OpenAI if available (even without rate limit check - let API decide)
        if (clientSecondary != null && rateLimitManager == null) {
            return clientSecondary;
        }

        // Both providers rate limited - return null to fail fast rather than waste API calls
        log.warn("All LLM providers are rate limited; no client available");
        return null;
    }

    private OpenAIClient selectClientForBlocking() {
        return selectClientForStreaming();
    }

    private boolean isPrimaryClient(OpenAIClient client) {
        return Objects.equals(client, clientPrimary);
    }

    private boolean isRateLimit(Throwable throwable) {
        if (throwable instanceof RateLimitException) {
            return true;
        }
        if (throwable instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() == 429;
        }
        return false;
    }
    
    private boolean isRetryablePrimaryFailure(Throwable throwable) {
        if (isRateLimit(throwable)) {
            return true;
        }
        if (throwable instanceof OpenAIIoException) {
            return true;
        }
        if (throwable instanceof InterruptedException) {
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
        log.warn("[{}] Temporarily disabled for {}s due to failure",
                RateLimitManager.ApiProvider.GITHUB_MODELS.getName(), seconds);
    }

}
