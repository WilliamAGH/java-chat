package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.concurrent.TimeoutException;

@Service
public class ResilientApiClient {
    private static final Logger log = LoggerFactory.getLogger(ResilientApiClient.class);
    
    private final WebClient webClient;
    private final RateLimitManager rateLimitManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;
    
    @Value("${GITHUB_TOKEN:}")
    private String githubToken;
    
    @Value("${OPENAI_MODEL:gpt-5}")
    private String model;
    
    @Value("${APP_API_TIMEOUT_SECONDS:30}")
    private int apiTimeoutSeconds;
    
    @Value("${APP_MAX_RETRIES:3}")
    private int maxRetries;

    // Diagnostics: control raw chunk logging noise during streaming
    @Autowired
    private com.williamcallahan.javachat.config.AppProperties appProps;
    
    public ResilientApiClient(WebClient.Builder webClientBuilder, RateLimitManager rateLimitManager) {
        this.webClient = webClientBuilder.build();
        this.rateLimitManager = rateLimitManager;
    }
    
    /**
     * Remove any leaked SSE protocol artifacts from model text deltas.
     * Some providers or proxies can forward merged lines that still include
     * "data:" or "event:" prefixes. We normalize by stripping those prefixes
     * both at line starts and when accidentally left inline between tokens.
     */
    @SuppressWarnings("unused")
    private String stripSseArtifacts(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        // Remove line-start SSE fields
        out = out.replaceAll("(?m)^\\s*data:\\s*", "");
        out = out.replaceAll("(?m)^\\s*event:\\s*\\w+\\s*", "");
        out = out.replaceAll("(?m)^\\s*id:\\s*.*$", "");
        // Remove stray inline occurrences caused by merged lines
        out = out.replaceAll("\\sdata:\\s*", " ");
        out = out.replaceAll("\\sevent:\\s*\\w+\\s*", " ");
        return out;
    }
    
    public Mono<String> callLLM(String prompt, double temperature) {
        return callWithFallback(prompt, temperature, false)
            .next()
            .timeout(Duration.ofSeconds(apiTimeoutSeconds))
            .doOnError(TimeoutException.class, e -> 
                log.warn("API call timed out after {} seconds", apiTimeoutSeconds))
            .onErrorResume(e -> {
                log.error("All API providers failed", e);
                return Mono.empty();
            });
    }
    
    public Flux<String> streamLLM(String prompt, double temperature) {
        // DIAGNOSTIC: raw prompt preview
        String preview = prompt.substring(0, Math.min(500, prompt.length()));
        log.info("[DIAG] API submission preview=\n{}", preview);
        return callWithFallback(prompt, temperature, true)
            .timeout(Duration.ofSeconds(apiTimeoutSeconds))
            .doOnError(TimeoutException.class, e -> 
                log.warn("API streaming timed out after {} seconds", apiTimeoutSeconds));
    }
    
    private Flux<String> callWithFallback(String prompt, double temperature, boolean stream) {
        RateLimitManager.ApiProvider provider = rateLimitManager.selectBestProvider();
        
        if (provider == null) {
            log.error("All API providers are rate limited or unavailable");
            return Flux.error(new RuntimeException("All API providers are currently unavailable due to rate limits"));
        }
        
        log.debug("Selected provider: {}", provider.getName());
        
        return switch (provider) {
            case OPENAI -> callOpenAI(prompt, temperature, stream)
                .doOnSubscribe(s -> rateLimitManager.recordSuccess(provider))
                .onErrorResume(e -> handleError(e, provider, prompt, temperature, stream));
                
            case GITHUB_MODELS -> callGitHubModels(prompt, temperature, stream)
                .doOnSubscribe(s -> rateLimitManager.recordSuccess(provider))
                .onErrorResume(e -> handleError(e, provider, prompt, temperature, stream));
                
            case LOCAL -> callLocalModel(prompt, temperature, stream)
                .doOnSubscribe(s -> rateLimitManager.recordSuccess(provider))
                .onErrorResume(e -> handleError(e, provider, prompt, temperature, stream));
        };
    }
    
    private Flux<String> handleError(Throwable error, RateLimitManager.ApiProvider failedProvider, 
                                     String prompt, double temperature, boolean stream) {
        if (isRateLimitError(error)) {
            // Use enhanced rate limit recording with header extraction
            rateLimitManager.recordRateLimitFromException(failedProvider, error);
            log.warn("Provider {} hit rate limit, trying next provider", failedProvider.getName());
        } else {
            log.error("Provider {} failed with error: {}", failedProvider.getName(), error.getMessage());
            if (error instanceof WebClientResponseException) {
                WebClientResponseException wce = (WebClientResponseException) error;
                log.error("Response body: {}", wce.getResponseBodyAsString());
            }
        }
        
        RateLimitManager.ApiProvider nextProvider = rateLimitManager.selectBestProvider();
        if (nextProvider != null && nextProvider != failedProvider) {
            log.info("Falling back from {} to {}", failedProvider.getName(), nextProvider.getName());
            return callWithFallback(prompt, temperature, stream);
        }
        
        return Flux.error(error);
    }
    
    private Flux<String> callOpenAI(String prompt, double temperature, boolean stream) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            return Flux.error(new RuntimeException("OpenAI API key not configured"));
        }

        // GPT-5 is available and working!
        String openaiModel = model;

        // Build request body based on model requirements
        Map<String, Object> body;
        if (model.equals("gpt-5") || model.equals("gpt-5-chat")) {
            // GPT-5 specific requirements:
            // 1. Use max_completion_tokens instead of max_tokens
            // 2. Temperature must be 1 or omitted
            // 3. Use minimal reasoning_effort for faster responses
            body = Map.of(
                "model", "gpt-5",
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_completion_tokens", 2000,
                "reasoning_effort", "minimal",
                "stream", stream
            );
        } else {
            // Standard OpenAI models (gpt-4o-mini, etc)
            body = Map.of(
                "model", openaiModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", temperature,
                "stream", stream
            );
        }

        if (!stream) {
            return webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + openaiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
.retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractContent)
                .flux();
        } else {
            // diag counter toggled via log level; suppress unused warning when disabled
            @SuppressWarnings("unused") final java.util.concurrent.atomic.AtomicInteger diagCounter = new java.util.concurrent.atomic.AtomicInteger(0);
            // For SSE streaming, we need to handle the event stream format properly
            return webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + openaiApiKey)
                .header("Accept", "text/event-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                // WebFlux returns raw JSON chunks, not SSE format
                .flatMap(chunk -> {
                    if (chunk == null || chunk.trim().isEmpty() || chunk.equals("[DONE]")) {
                        return Flux.empty();
                    }
                    
                    try {
                        // Parse the raw JSON chunk directly
                        Map<String, Object> data = objectMapper.readValue(chunk, new TypeReference<Map<String, Object>>() {});
                        
                        // Extract content from the delta field
                        Object choicesObj = data.get("choices");
                        if (choicesObj instanceof List) {
                            List<?> choices = (List<?>) choicesObj;
                            if (!choices.isEmpty()) {
                                Object firstChoiceObj = choices.get(0);
                                if (firstChoiceObj instanceof Map) {
                                    Map<?, ?> firstChoice = (Map<?, ?>) firstChoiceObj;
                                    Object deltaObj = firstChoice.get("delta");
                                    if (deltaObj instanceof Map) {
                                        Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                        Object content = delta.get("content");
                                        if (content != null && !content.toString().isEmpty()) {
                                            String text = content.toString();
                                            log.debug("[GPT-5] Extracted content: {}", text);
                                            return Flux.just(text);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse chunk as JSON, might be SSE format: {}", e.getMessage());
                        // Fall back to SSE parsing if it's not raw JSON
                        String content = extractStreamContent(chunk);
                        if (content != null && !content.isEmpty()) {
                            return Flux.just(content);
                        }
                    }
                    return Flux.empty();
                });
        }
    }
    
    private Flux<String> callGitHubModels(String prompt, double temperature, boolean stream) {
        if (githubToken == null || githubToken.isBlank()) {
            return Flux.error(new RuntimeException("GitHub token not configured"));
        }

        // GitHub Models requires "openai/" prefix for OpenAI models
        // Fallback to gpt-4o-mini if gpt-5 is not available
        String baseModel = model.equals("gpt-5") ? "gpt-4o-mini" : model;
        String githubModel = baseModel.startsWith("openai/") ? baseModel : "openai/" + baseModel;

        // GitHub Models has stricter payload size limits - truncate if necessary
        String truncatedPrompt = truncateForGitHubModels(prompt);
        if (truncatedPrompt.length() < prompt.length()) {
            log.info("Truncated prompt for GitHub Models: {} chars -> {} chars",
                prompt.length(), truncatedPrompt.length());
        }

        Map<String, Object> body = Map.of(
            "model", githubModel,
            "messages", List.of(Map.of("role", "user", "content", truncatedPrompt)),
            "temperature", temperature,
            "stream", stream
        );
        
        String url = "https://models.github.ai/inference/v1/chat/completions";
        
        if (!stream) {
            return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + githubToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
.retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractContent)
                .flux();
        } else {
            final java.util.concurrent.atomic.AtomicInteger diagCounter = new java.util.concurrent.atomic.AtomicInteger(0);
            return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + githubToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(chunk -> {
                    boolean diagStreamChunkLogging = appProps.getDiagnostics().isStreamChunkLogging();
                    int diagStreamChunkSample = appProps.getDiagnostics().getStreamChunkSample();
                    if (diagStreamChunkLogging) {
                        int n = diagCounter.incrementAndGet();
                        if (diagStreamChunkSample <= 0 || (n % diagStreamChunkSample) == 0) {
                            String p = chunk.length() > 200 ? chunk.substring(0, 200) + "…" : chunk;
                            log.debug("[DIAG] raw stream chunk: {}", p.replace("\n", "\\n"));
                        }
                    }
                    return extractStreamContent(chunk);
                });
        }
    }
    
    private Flux<String> callLocalModel(String prompt, double temperature, boolean stream) {
        return Flux.error(new RuntimeException("Local model not configured"));
    }
    
    private String extractContent(Map<String, Object> response) {
        try {
            // Standard OpenAI chat completions format
            Object choicesObj = response.get("choices");
            if (choicesObj instanceof List) {
                List<?> choices = (List<?>) choicesObj;
                if (!choices.isEmpty()) {
                    Object firstChoiceObj = choices.get(0);
                    if (firstChoiceObj instanceof Map) {
                        Map<?, ?> firstChoice = (Map<?, ?>) firstChoiceObj;
                        Object messageObj = firstChoice.get("message");
                        if (messageObj instanceof Map) {
                            Map<?, ?> message = (Map<?, ?>) messageObj;
                            Object content = message.get("content");
                            return content != null ? content.toString() : "";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract content from response", e);
        }
        return "";
    }
    
    private String extractStreamContent(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        
        // Log the raw chunk for debugging
        if (chunk.contains("data:") && !chunk.contains("[DONE]")) {
            log.debug("[SSE] Processing chunk: {}", 
                chunk.length() > 500 ? chunk.substring(0, 500) + "..." : chunk);
        }
        
        // Split by newlines to handle multiple SSE events in one chunk
        String[] lines = chunk.split("\n");
        
        for (String line : lines) {
            // Skip empty lines and SSE comments
            if (line.trim().isEmpty() || line.startsWith(":")) {
                continue;
            }
            
            // Process each data line
            if (line.startsWith("data: ")) {
                String dataContent = line.substring(6).trim();
                
                // Skip [DONE] marker
                if (dataContent.equals("[DONE]") || dataContent.isEmpty()) {
                    continue;
                }
                
                try {
                    Map<String, Object> data = objectMapper.readValue(dataContent, new TypeReference<Map<String, Object>>() {});
                    
                    // Standard OpenAI chat completions streaming format
                    Object choicesObj = data.get("choices");
                    if (choicesObj instanceof List) {
                        List<?> choices = (List<?>) choicesObj;
                        if (!choices.isEmpty()) {
                            Object firstChoiceObj = choices.get(0);
                            if (firstChoiceObj instanceof Map) {
                                Map<?, ?> firstChoice = (Map<?, ?>) firstChoiceObj;
                                Object deltaObj = firstChoice.get("delta");
                                if (deltaObj instanceof Map) {
                                    Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                    Object content = delta.get("content");
                                    if (content != null && !content.toString().isEmpty()) {
                                        String text = content.toString();
                                        result.append(text);
                                        log.debug("[SSE] Extracted text: {}", text);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SSE] Failed to parse data line: {} - Error: {}", 
                        dataContent.length() > 100 ? dataContent.substring(0, 100) + "..." : dataContent,
                        e.getMessage());
                }
            } else if (line.startsWith("data:")) {
                // Handle case where there's no space after "data:"
                String dataContent = line.substring(5).trim();
                if (!dataContent.isEmpty() && !dataContent.equals("[DONE]")) {
                    try {
                        Map<String, Object> data = objectMapper.readValue(dataContent, new TypeReference<Map<String, Object>>() {});
                        // Same parsing logic as above
                        Object choicesObj = data.get("choices");
                        if (choicesObj instanceof List) {
                            List<?> choices = (List<?>) choicesObj;
                            if (!choices.isEmpty()) {
                                Object firstChoiceObj = choices.get(0);
                                if (firstChoiceObj instanceof Map) {
                                    Map<?, ?> firstChoice = (Map<?, ?>) firstChoiceObj;
                                    Object deltaObj = firstChoice.get("delta");
                                    if (deltaObj instanceof Map) {
                                        Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                        Object content = delta.get("content");
                                        if (content != null && !content.toString().isEmpty()) {
                                            result.append(content.toString());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore parse errors for malformed data
                    }
                }
            }
        }
        
        return result.toString();
    }
    
    private boolean isRateLimitError(Throwable error) {
        if (error instanceof WebClientResponseException) {
            WebClientResponseException webError = (WebClientResponseException) error;
            return webError.getStatusCode().value() == 429;
        }
        
        String message = error.getMessage();
        return message != null && (
            message.contains("429") || 
            message.contains("rate limit") || 
            message.contains("RateLimitReached")
        );
    }
    
    private boolean isRetryableError(Throwable error) {
        if (error instanceof WebClientResponseException) {
            WebClientResponseException webError = (WebClientResponseException) error;
            int status = webError.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        
        String message = error.getMessage();
        return message != null && (
            message.contains("timeout") || 
            message.contains("connection")
        );
    }
    
    private String truncateForGitHubModels(String prompt) {
        // GitHub Models has a roughly 128K character limit for the entire request
        // We'll be conservative and limit the prompt to 100K characters to leave room for metadata
        final int MAX_PROMPT_LENGTH = 100000;
        
        if (prompt.length() <= MAX_PROMPT_LENGTH) {
            return prompt;
        }
        
        // Keep the most recent context and the current question
        // Try to find the last user message in the prompt
        String marker = "User:";
        int lastUserIndex = prompt.lastIndexOf(marker);
        
        if (lastUserIndex > 0 && lastUserIndex > prompt.length() - 10000) {
            // If the last user message is near the end, preserve it and truncate older history
            String recentContext = prompt.substring(Math.max(0, prompt.length() - MAX_PROMPT_LENGTH));
            
            // Try to find a clean break point (paragraph or message boundary)
            int breakPoint = recentContext.indexOf("\n\n");
            if (breakPoint > 0 && breakPoint < 1000) {
                recentContext = recentContext.substring(breakPoint + 2);
            }
            
            return "[Previous context truncated due to size limits]\n\n" + recentContext;
        } else {
            // Fallback: just take the most recent portion
            return "[Previous context truncated due to size limits]\n\n" + 
                   prompt.substring(prompt.length() - MAX_PROMPT_LENGTH);
        }
    }
}