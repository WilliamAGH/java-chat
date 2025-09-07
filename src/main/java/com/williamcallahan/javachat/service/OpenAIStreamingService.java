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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
 

import java.util.concurrent.atomic.AtomicReference;
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
    
    private OpenAIClient clientPrimary;   // Prefer GitHub Models when available
    private OpenAIClient clientSecondary; // Fallback to OpenAI when available
    private boolean isAvailable = false;
    private String primaryDescription = null;
    private String secondaryDescription = null;
    private final RateLimitManager rateLimitManager;
    // When primary (GitHub Models) fails with rate limit/timeout/auth, temporarily avoid using it
    private volatile long primaryBackoffUntilEpochMs = 0L;
    
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
                this.primaryDescription = "GitHub Models (" + githubModelsBaseUrl + ")";
            }
            if (openaiApiKey != null && !openaiApiKey.isBlank()) {
                log.info("Initializing OpenAI client with OpenAI API (fallback)");
                this.clientSecondary = OpenAIOkHttpClient.builder()
                        .apiKey(openaiApiKey)
                        .baseUrl("https://api.openai.com/v1")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                log.info("OpenAI client initialized successfully with OpenAI API");
                this.secondaryDescription = "OpenAI (https://api.openai.com/v1)";
            }
            this.isAvailable = (clientPrimary != null) || (clientSecondary != null);
            if (!this.isAvailable) {
                log.warn("No API credentials found (GITHUB_TOKEN or OPENAI_API_KEY) - OpenAI streaming will not be available");
                this.isAvailable = false;
            }
        } catch (Exception e) {
            log.error("Failed to initialize OpenAI client", e);
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
        log.debug("Starting OpenAI stream for prompt length: {}", prompt.length());
        
        return Flux.<String>create(sink -> {
            try {
                ChatCompletionCreateParams params = buildChatParams(prompt, temperature);
                OpenAIClient first = selectClientForStreaming();
                ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
                AtomicReference<ChatCompletion> finalCompletion = new AtomicReference<>();
                
                RateLimitManager.ApiProvider firstProvider = (first == clientPrimary)
                        ? RateLimitManager.ApiProvider.GITHUB_MODELS
                        : RateLimitManager.ApiProvider.OPENAI;
                log.info("[LLM] Streaming via {}", describeProvider(first));
                try (StreamResponse<ChatCompletionChunk> streamResponse =
                        first.chat().completions().createStreaming(params)) {
                    
                    streamResponse.stream()
                        .peek(accumulator::accumulate)  // Accumulate for final result
                        .forEach(chunk -> {
                            log.debug("Raw chunk: {}", chunk);
                            chunk.choices().forEach(choice -> {
                                log.debug("Choice delta: {}", choice.delta());
                                choice.delta().content().ifPresent(content -> {
                                    log.debug("Received content chunk: '{}'", content);
                                    sink.next(content);
                                });
                            });
                        });
                    
                    // Get the complete response for any post-processing needs
                    finalCompletion.set(accumulator.chatCompletion());
                    log.debug("Stream completed successfully");
                    if (rateLimitManager != null) {
                        rateLimitManager.recordSuccess(firstProvider);
                    }
                    sink.complete();
                    
                } catch (Exception e) {
                    log.error("[LLM] Primary streaming failed ({}): {}", describeProvider(first), summarize(e));
                    if (first == clientPrimary && isRetryablePrimaryFailure(e)) {
                        markPrimaryBackoff("stream failure: " + summarize(e));
                        if (rateLimitManager != null) {
                            rateLimitManager.recordRateLimit(RateLimitManager.ApiProvider.GITHUB_MODELS, e.getMessage());
                        }
                    }
                    // Fallback once if secondary available
                    try {
                        OpenAIClient alt = selectAlternateClient();
                        if (alt != null) {
                            log.info("[LLM] Retrying streaming with alternate provider: {}", describeProvider(alt));
                            try (StreamResponse<ChatCompletionChunk> altResponse =
                                         alt.chat().completions().createStreaming(params)) {
                                altResponse.stream()
                                        .peek(com.openai.helpers.ChatCompletionAccumulator.create()::accumulate)
                                        .forEach(chunk -> chunk.choices().forEach(choice ->
                                                choice.delta().content().ifPresent(sink::next)));
                                if (rateLimitManager != null) {
                                    rateLimitManager.recordSuccess(RateLimitManager.ApiProvider.OPENAI);
                                }
                                sink.complete();
                                return;
                            }
                        }
                    } catch (Exception ex) {
                        log.error("[LLM] Alternate provider streaming failed ({}): {}", describeProvider(selectAlternateClient()), summarize(ex));
                    }
                    sink.error(e);
                }
                
            } catch (Exception e) {
                log.error("Error setting up OpenAI stream", e);
                sink.error(e);
            }
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
        return Mono.fromCallable(() -> {
            OpenAIClient first = selectClientForBlocking();
            ChatCompletionCreateParams params = buildChatParams(truncatedPrompt, temperature);
            try {
                log.info("[LLM] Complete via {}", describeProvider(first));
                ChatCompletion completion = first.chat().completions().create(params);
                if (rateLimitManager != null) {
                    rateLimitManager.recordSuccess(first == clientPrimary
                            ? RateLimitManager.ApiProvider.GITHUB_MODELS
                            : RateLimitManager.ApiProvider.OPENAI);
                }
                return completion.choices().stream()
                        .findFirst()
                        .flatMap(choice -> choice.message().content())
                        .orElse("");
            } catch (Exception primaryError) {
                if (first == clientPrimary && isRetryablePrimaryFailure(primaryError)) {
                    markPrimaryBackoff("complete failure: " + summarize(primaryError));
                    if (rateLimitManager != null) {
                        rateLimitManager.recordRateLimit(RateLimitManager.ApiProvider.GITHUB_MODELS, primaryError.getMessage());
                    }
                }
                OpenAIClient alt = selectAlternateClient();
                if (alt != null) {
                    log.warn("[LLM] Primary complete failed ({}), retrying with {}: {}",
                            describeProvider(first), describeProvider(alt), summarize(primaryError));
                    ChatCompletion completion = alt.chat().completions().create(params);
                    if (rateLimitManager != null) {
                        rateLimitManager.recordSuccess(RateLimitManager.ApiProvider.OPENAI);
                    }
                    return completion.choices().stream()
                            .findFirst()
                            .flatMap(choice -> choice.message().content())
                            .orElse("");
                }
                throw primaryError;
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

        // Attempt to set reasoning_effort=minimal when supported by the SDK
        trySetReasoningEffort(builder);

        return builder.build();
    }

    /**
     * Best-effort application of reasoning_effort="minimal" without creating a compile-time
     * dependency on specific SDK versions. Uses reflection to call either a typed
     * reasoningEffort(Enum) method, or falls back to an extra body map if available.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void trySetReasoningEffort(ChatCompletionCreateParams.Builder builder) {
        try {
            // 1) Preferred: builder.reasoningEffort(ReasoningEffort.MINIMAL)
            for (Method m : builder.getClass().getMethods()) {
                if ("reasoningEffort".equals(m.getName()) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isEnum()) {
                        Object minimal = Enum.valueOf((Class<Enum>) paramType, "MINIMAL");
                        m.invoke(builder, minimal);
                        log.info("[LLM] reasoning_effort=MINIMAL (SDK enum)");
                        return;
                    }
                }
            }

            // 2) Fallback: Chat Completions supports only top-level "reasoning_effort"
            //    Do NOT send a nested "reasoning" object on this endpoint.
            builder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from("minimal"));
            log.info("[LLM] reasoning_effort set via additional body property");
            return;
        } catch (Exception ex) {
            log.debug("Skipping reasoning_effort due to SDK compatibility: {}", ex.toString());
        }
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

        int limit = ("gpt-5".equalsIgnoreCase(model) || "gpt-5-chat".equalsIgnoreCase(model))
            ? MAX_CHARS_GPT5_INPUT
            : MAX_CHARS_DEFAULT;

        if (prompt.length() <= limit) return prompt;

        // Prefer keeping the most recent context and user message
        String marker = "User:";
        int lastUserIdx = prompt.lastIndexOf(marker);
        if (lastUserIdx > 0 && lastUserIdx > prompt.length() - 2_000) {
            String recent = prompt.substring(Math.max(0, prompt.length() - limit));
            // Trim to a clean-ish boundary
            int para = recent.indexOf("\n\n");
            if (para > 0 && para < 2_000) recent = recent.substring(para + 2);
            int ctx = recent.indexOf("[CTX ");
            if (ctx > 0 && ctx < 2_000) recent = recent.substring(ctx);
            return "[Context truncated due to GPT-5 8K input limit]\n\n" + recent;
        }
        return "[Context truncated due to model input limit]\n\n" + prompt.substring(prompt.length() - limit);
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

    private OpenAIClient selectAlternateClient() {
        if (clientPrimary != null && clientSecondary != null) {
            // If we failed on primary, return secondary
            return clientSecondary;
        }
        return null;
    }

    private String describeProvider(OpenAIClient client) {
        if (client == null) return "none";
        if (client == clientPrimary && primaryDescription != null) return primaryDescription;
        if (client == clientSecondary && secondaryDescription != null) return secondaryDescription;
        return "unknown";
    }

    private String summarize(Exception e) {
        String s = e.toString();
        if (s.length() > 180) return s.substring(0, 180) + "…";
        return s;
    }
    
    private boolean isRateLimit(Throwable t) {
        if (t instanceof RateLimitException) return true;
        String m = t.getMessage();
        return m != null && (m.contains("Rate limit") || m.contains("429"));
    }
    
    private boolean isRetryablePrimaryFailure(Throwable t) {
        // Treat common transient failures as retryable to enable fast fallback
        return isRateLimit(t)
                || t instanceof java.util.concurrent.TimeoutException
                || t instanceof InterruptedException
                || (t.getMessage() != null && t.getMessage().toLowerCase().contains("sleep interrupted"))
                || t.toString().contains("401")
                || t.toString().contains("403");
    }
    
    private boolean isPrimaryInBackoff() {
        return System.currentTimeMillis() < primaryBackoffUntilEpochMs;
    }
    
    private void markPrimaryBackoff(String reason) {
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, primaryBackoffSeconds));
        this.primaryBackoffUntilEpochMs = until;
        long seconds = Math.max(1, (until - System.currentTimeMillis()) / 1000);
        log.warn("Temporarily disabling primary provider {} for {}s due to {}",
                describeProvider(clientPrimary), seconds, reason);
    }
}
