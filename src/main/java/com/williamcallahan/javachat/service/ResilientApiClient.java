package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.concurrent.TimeoutException;

@Service
@SuppressWarnings("unchecked")
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
    
    public ResilientApiClient(WebClient.Builder webClientBuilder, RateLimitManager rateLimitManager) {
        this.webClient = webClientBuilder.build();
        this.rateLimitManager = rateLimitManager;
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
        
        // GPT-5 uses a different API structure
        Map<String, Object> body;
        String endpoint;
        
        if ("gpt-5".equals(model)) {
            // GPT-5 uses the new responses API with minimal reasoning
            body = Map.of(
                "model", model,
                "input", List.of(
                    Map.of(
                        "role", "user",
                        "content", prompt
                    )
                ),
                "reasoning", Map.of("effort", "minimal"),
                "stream", stream
            );
            endpoint = "https://api.openai.com/v1/responses";
        } else {
            // GPT-4 and earlier use chat completions
            body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", temperature,
                "stream", stream
            );
            endpoint = "https://api.openai.com/v1/chat/completions";
        }
        
        if (!stream) {
            return webClient.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + openaiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractContent)
                .flux();
        } else {
            return webClient.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + openaiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractStreamContent);
        }
    }
    
    private Flux<String> callGitHubModels(String prompt, double temperature, boolean stream) {
        if (githubToken == null || githubToken.isBlank()) {
            return Flux.error(new RuntimeException("GitHub token not configured"));
        }
        
        // GitHub Models requires "openai/" prefix for OpenAI models
        String githubModel = model.startsWith("openai/") ? model : "openai/" + model;
        
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
                .bodyToMono(Map.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractContent)
                .flux();
        } else {
            return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + githubToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(1))
                    .filter(this::isRetryableError))
                .map(this::extractStreamContent);
        }
    }
    
    private Flux<String> callLocalModel(String prompt, double temperature, boolean stream) {
        return Flux.error(new RuntimeException("Local model not configured"));
    }
    
    private String extractContent(Map<String, Object> response) {
        try {
            // Check if this is a GPT-5 response format
            if (response.containsKey("output")) {
                Object outputObj = response.get("output");
                if (outputObj instanceof List) {
                    List<?> output = (List<?>) outputObj;
                    if (!output.isEmpty()) {
                        Object firstOutputObj = output.get(0);
                        if (firstOutputObj instanceof Map) {
                            Map<?, ?> firstOutput = (Map<?, ?>) firstOutputObj;
                            Object content = firstOutput.get("content");
                            if (content instanceof String) {
                                return (String) content;
                            } else if (content instanceof Map) {
                                Map<?, ?> contentMap = (Map<?, ?>) content;
                                Object text = contentMap.get("text");
                                return text != null ? text.toString() : "";
                            }
                        }
                    }
                }
            }

            // Traditional GPT-4 format
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
        try {
            if (chunk.startsWith("data: ")) {
                chunk = chunk.substring(6);
            }
            if (chunk.equals("[DONE]")) {
                return "";
            }

            Map<String, Object> data = objectMapper.readValue(chunk, new TypeReference<Map<String, Object>>() {});

            // Check if this is a GPT-5 streaming event
            String type = (String) data.get("type");
            if (type != null) {
                // Handle GPT-5 streaming events
                if ("response.output_text.delta".equals(type)) {
                    // In GPT-5, the delta field contains the text directly
                    Object delta = data.get("delta");
                    return delta != null ? delta.toString() : "";
                }
                return ""; // Other event types don't contain text deltas
            }

            // Traditional GPT-4 streaming format
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
                            return content != null ? content.toString() : "";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse streaming chunk: {}", chunk);
        }
        return "";
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