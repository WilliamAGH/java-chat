package com.williamcallahan.javachat.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.ChatModel;
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

import java.lang.reflect.Method;
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
    
    @Value("${OPENAI_MODEL:gpt-5}")
    private String model;
    
    @Value("${GITHUB_MODELS_BASE_URL:https://models.github.ai/inference/v1}")
    private String githubModelsBaseUrl;
    
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
                this.clientPrimary = OpenAIOkHttpClient.builder()
                        .apiKey(githubToken)
                        .baseUrl(githubModelsBaseUrl)
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                log.info("OpenAI client initialized successfully with GitHub Models");
            }
            if (openaiApiKey != null && !openaiApiKey.isBlank()) {
                log.info("Initializing OpenAI client with OpenAI API (fallback)");
                this.clientSecondary = OpenAIOkHttpClient.builder()
                        .apiKey(openaiApiKey)
                        .baseUrl("https://api.openai.com/v1")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                log.info("OpenAI client initialized successfully with OpenAI API");
            }
            this.isAvailable = (clientPrimary != null) || (clientSecondary != null);
            if (!this.isAvailable) {
                log.warn("No API credentials found (GITHUB_TOKEN or OPENAI_API_KEY) - OpenAI streaming will not be available");
            }
        } catch (RuntimeException exception) {
            log.error("Failed to initialize OpenAI client (exception type: {})",
                exception.getClass().getSimpleName());
            this.isAvailable = false;
        }
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
        
        return Flux.<String>create(sink -> {
            try {
                ChatCompletionCreateParams params = buildChatParams(prompt, temperature);
                OpenAIClient streamingClient = selectClientForStreaming();
                if (streamingClient == null) {
                    log.error("No OpenAI-compatible client is configured or available for streaming. "
                            + "Check API credentials and configuration.");
                    sink.error(new IllegalStateException(
                            "No OpenAI-compatible client is configured or available for streaming."));
                    return;
                }
                
                executeStreamingRequest(streamingClient, params, sink);
                
            } catch (RuntimeException exception) {
                log.error("Error setting up OpenAI stream (exception type: {})",
                    exception.getClass().getSimpleName());
                sink.error(exception);
            }
        })
        // Move blocking SDK stream consumption off the servlet thread.
        // Prevents thread starvation and aligns with Reactor best practices.
        .subscribeOn(Schedulers.boundedElastic());
    }

    private void executeStreamingRequest(OpenAIClient client, ChatCompletionCreateParams params, reactor.core.publisher.FluxSink<String> sink) {
        ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
        RateLimitManager.ApiProvider activeProvider = isPrimaryClient(client)
                ? RateLimitManager.ApiProvider.GITHUB_MODELS
                : RateLimitManager.ApiProvider.OPENAI;
        log.info("[LLM] Streaming started");
        
        try (StreamResponse<ChatCompletionChunk> responseStream =
                client.chat().completions().createStreaming(params)) {
            
            responseStream.stream()
                .peek(accumulator::accumulate)  // Accumulate for final result
                .forEach(chunk -> {
                    chunk.choices().forEach(choice -> {
                        choice.delta().content().ifPresent(sink::next);
                    });
                });
            
            log.debug("Stream completed successfully");
            if (rateLimitManager != null) {
                rateLimitManager.recordSuccess(activeProvider);
            }
            sink.complete();
            
        } catch (RuntimeException exception) {
            handleStreamingFailure(exception, client, sink);
        }
    }

    private void handleStreamingFailure(RuntimeException exception, OpenAIClient client, reactor.core.publisher.FluxSink<String> sink) {
        log.error("[LLM] Streaming failed (exception type: {})", exception.getClass().getSimpleName());
        if (isPrimaryClient(client) && isRetryablePrimaryFailure(exception)) {
            markPrimaryBackoff();
            if (rateLimitManager != null) {
                rateLimitManager.recordRateLimit(RateLimitManager.ApiProvider.GITHUB_MODELS, exception.getMessage());
            }
        }
        sink.error(exception);
    }
    
    /**
     * Get a complete (non-streaming) response from OpenAI (async wrapper).
     */
    public Mono<String> complete(String prompt, double temperature) {
        final String truncatedPrompt = truncatePromptForModel(prompt);
        return Mono.defer(() -> {
            OpenAIClient blockingClient = selectClientForBlocking();
            if (blockingClient == null) {
                log.error("No OpenAI-compatible client is configured or available for completion. "
                        + "Check API credentials and configuration.");
                return Mono.error(new IllegalStateException(
                        "No OpenAI-compatible client is configured or available for completion."));
            }
            ChatCompletionCreateParams params = buildChatParams(truncatedPrompt, temperature);
            try {
                log.info("[LLM] Complete started");
                ChatCompletion completion = blockingClient.chat().completions().create(params);
                if (rateLimitManager != null) {
                    rateLimitManager.recordSuccess(isPrimaryClient(blockingClient)
                            ? RateLimitManager.ApiProvider.GITHUB_MODELS
                            : RateLimitManager.ApiProvider.OPENAI);
                }
                String response = completion.choices().stream()
                        .findFirst()
                        .flatMap(choice -> choice.message().content())
                        .orElse("");
                return Mono.just(response);
            } catch (RuntimeException primaryException) {
                if (isPrimaryClient(blockingClient) && isRetryablePrimaryFailure(primaryException)) {
                    markPrimaryBackoff();
                    if (rateLimitManager != null) {
                        rateLimitManager.recordRateLimit(RateLimitManager.ApiProvider.GITHUB_MODELS, primaryException.getMessage());
                    }
                }
                return Mono.error(primaryException);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private ChatCompletionCreateParams buildChatParams(String prompt, double temperature) {
        // Enforce GPT-5; never regress the model
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(ChatModel.GPT_5);

        // GPT-5: omit temperature and set conservative max output tokens
        builder.maxCompletionTokens(4000);
        log.debug("Using GPT-5 configuration (no regression)");

        // Set reasoning_effort=minimal; fail fast if unsupported
        applyReasoningEffort(builder);

        return builder.build();
    }

    /**
     * Applies reasoning_effort="minimal" for GPT-5 prompts.
     * Supports both Java enums and OpenAI SDK's Kotlin sealed classes.
     *
     * @throws IllegalStateException if the SDK method exists but MINIMAL value cannot be resolved
     */
    private void applyReasoningEffort(ChatCompletionCreateParams.Builder builder) {
        Method reasoningMethod = findReasoningEffortMethod(builder);
        if (reasoningMethod == null) {
            // No SDK method available - use body property as primary approach
            builder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from("minimal"));
            log.info("[LLM] reasoning_effort set via additional body property (no SDK method)");
            return;
        }

        // SDK method exists - must succeed or fail fast
        try {
            Class<?> parameterType = reasoningMethod.getParameterTypes()[0];
            Object minimal = resolveMinimalReasoningEffort(parameterType);
            reasoningMethod.invoke(builder, minimal);
            log.info("[LLM] reasoning_effort=MINIMAL (SDK type: {})", parameterType.getSimpleName());
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            throw new IllegalStateException("SDK reasoningEffort method exists but invocation failed", exception);
        }
    }

    /**
     * Resolves the MINIMAL reasoning effort value for the given parameter type.
     * Handles OpenAI SDK Kotlin sealed classes (static field) and Java enums (fallback).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Static MINIMAL field (OpenAI SDK Kotlin companion object)</li>
     *   <li>Enum constant named MINIMAL (Java enum fallback)</li>
     * </ol>
     *
     * @throws IllegalStateException if MINIMAL constant cannot be found via either mechanism
     */
    private Object resolveMinimalReasoningEffort(Class<?> parameterType) throws ReflectiveOperationException {
        // Try static MINIMAL field first (works for OpenAI SDK Kotlin sealed classes)
        try {
            java.lang.reflect.Field minimalField = parameterType.getField("MINIMAL");
            Object minimal = minimalField.get(null);
            log.debug("[LLM] Resolved MINIMAL from static field on {}", parameterType.getSimpleName());
            return minimal;
        } catch (NoSuchFieldException noStaticField) {
            // Static field not found - expected for non-Kotlin types; fall through to enum check
            log.info("[LLM] No static MINIMAL field on {} (trying enum fallback)", parameterType.getSimpleName());
        }

        // Java enum: look up MINIMAL constant by name
        if (parameterType.isEnum()) {
            for (Object constant : parameterType.getEnumConstants()) {
                if (constant instanceof Enum<?> enumConstant && "MINIMAL".equals(enumConstant.name())) {
                    log.debug("[LLM] Resolved MINIMAL from enum constant on {}", parameterType.getSimpleName());
                    return constant;
                }
            }
            // Enum exists but has no MINIMAL constant - fail fast with clear error
            throw new IllegalStateException(
                "Enum type " + parameterType.getName() + " has no MINIMAL constant; "
                + "available: " + java.util.Arrays.toString(parameterType.getEnumConstants()));
        }

        // Neither static field nor enum - fail fast with specific error
        throw new IllegalStateException(
            "Unsupported reasoningEffort type: " + parameterType.getName()
            + " (no static MINIMAL field found and type is not an enum)");
    }

    /**
     * Finds the reasoningEffort setter method that accepts a ReasoningEffort (not Optional).
     * The SDK has two overloads; we need the one accepting the raw type for direct invocation.
     */
    private Method findReasoningEffortMethod(ChatCompletionCreateParams.Builder builder) {
        Method candidate = null;
        for (Method method : builder.getClass().getMethods()) {
            if ("reasoningEffort".equals(method.getName()) && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                // Skip the Optional<ReasoningEffort> overload
                if (java.util.Optional.class.isAssignableFrom(paramType)) {
                    continue;
                }
                // Skip JsonField<ReasoningEffort> overload
                if (paramType.getName().contains("JsonField")) {
                    continue;
                }
                candidate = method;
                // Prefer the non-wrapper overload (ReasoningEffort directly)
                if (!paramType.getName().contains("Optional")) {
                    return method;
                }
            }
        }
        return candidate;
    }
    
    
    
    // Model mapping removed to prevent unintended regression; GPT-5 is enforced
    
    /**
     * Truncate prompt conservatively based on model limits to avoid 413 errors.
     */
    private String truncatePromptForModel(String prompt) {
        if (prompt == null || prompt.isEmpty()) return prompt;
        // Approximate safe character budgets (chars ~ tokens * ~4)
        final int MAX_CHARS_GPT5_INPUT = 28_000; // ~7k tokens, under 8k input limit
        final int MAX_CHARS_DEFAULT = 400_000;   // generous for high-context models

        String modelId = AsciiTextNormalizer.toLowerAscii(model == null ? "" : model);
        int limit = ("gpt-5".equals(modelId) || "gpt-5-chat".equals(modelId))
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
    
    /**
     * Check if the OpenAI streaming service is properly configured and available.
     */
    public boolean isAvailable() {
        return isAvailable && (clientPrimary != null || clientSecondary != null);
    }

    private OpenAIClient selectClientForStreaming() {
        // Fast-fail preference for the day: if manager says OpenAI is available, use it directly
        if (rateLimitManager != null && clientSecondary != null && rateLimitManager.isProviderAvailable(RateLimitManager.ApiProvider.OPENAI)) {
            return clientSecondary;
        }

        boolean githubOk = clientPrimary != null && !isPrimaryInBackoff();
        if (rateLimitManager != null && clientPrimary != null) {
            githubOk = githubOk && rateLimitManager.isProviderAvailable(RateLimitManager.ApiProvider.GITHUB_MODELS);
        }
        if (githubOk) return clientPrimary;
        if (clientSecondary != null) {
            if (rateLimitManager == null || rateLimitManager.isProviderAvailable(RateLimitManager.ApiProvider.OPENAI)) {
                return clientSecondary;
            }
        }
        return clientPrimary; // may be null; upstream will handle availability
    }

    private OpenAIClient selectClientForBlocking() {
        return selectClientForStreaming();
    }

    private boolean isPrimaryClient(OpenAIClient client) {
        return Objects.equals(client, clientPrimary);
    }

    private boolean isRateLimit(Throwable throwable) {
        if (throwable instanceof RateLimitException) return true;
        String message = throwable.getMessage();
        return message != null && (message.contains("Rate limit") || message.contains("429"));
    }
    
    private boolean isRetryablePrimaryFailure(Throwable throwable) {
        // Treat common transient failures as retryable to enable fast fallback
        return isRateLimit(throwable)
                || throwable instanceof java.util.concurrent.TimeoutException
                || throwable instanceof InterruptedException
                || (throwable.getMessage() != null && AsciiTextNormalizer.toLowerAscii(throwable.getMessage()).contains("sleep interrupted"))
                || throwable.toString().contains("401")
                || throwable.toString().contains("403");
    }
    
    private boolean isPrimaryInBackoff() {
        return System.currentTimeMillis() < primaryBackoffUntilEpochMs;
    }
    
    private void markPrimaryBackoff() {
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, primaryBackoffSeconds));
        this.primaryBackoffUntilEpochMs = until;
        long seconds = Math.max(1, (until - System.currentTimeMillis()) / 1000);
        log.warn("Temporarily disabling primary provider for {}s", seconds);
    }

}
