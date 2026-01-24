package com.williamcallahan.javachat.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.model.ChatTurn;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes chat endpoints for streaming responses, session history management, and diagnostics.
 */
@RestController
@RequestMapping("/api/chat")
@PermitAll
@PreAuthorize("permitAll()")
public class ChatController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final double TEMPERATURE = 0.7;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 20;
    /** Buffer capacity for backpressure handling in streaming responses. */
    private static final int STREAM_BACKPRESSURE_BUFFER_SIZE = 512;

    /** SSE event type for error notifications sent to the client. */
    private static final String SSE_EVENT_ERROR = "error";
    /** SSE event type for diagnostic status events. */
    private static final String SSE_EVENT_STATUS = "status";
    /** SSE event type for primary text chunks. */
    private static final String SSE_EVENT_TEXT = "text";
    /** SSE comment content for heartbeats. */
    private static final String SSE_COMMENT_KEEPALIVE = "keepalive";

    /**
     * Monotonic request counter for correlating log entries within a single chat request.
     * Uses AtomicLong (vs timestamp+threadId) to guarantee uniqueness even under high concurrency
     * where multiple requests could start in the same millisecond on the same thread pool.
     */
    
    private final ChatService chatService;
    private final ChatMemoryService chatMemory;
    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}")
    private String localEmbeddingServerUrl;

	    @Value("${app.local-embedding.enabled:false}")
	    private boolean localEmbeddingEnabled;

	    /**
	     * Creates the chat controller wired to chat, retrieval, and markdown services.
	     *
	     * @param chatService chat orchestration service
	     * @param chatMemory conversation memory service
	     * @param openAIStreamingService streaming LLM client
	     * @param retrievalService retrieval service for diagnostics
	     * @param objectMapper JSON mapper for safe SSE serialization
	     * @param restTemplateBuilder builder for creating the RestTemplate
	     * @param exceptionBuilder shared exception response builder
	     */
	    public ChatController(ChatService chatService, ChatMemoryService chatMemory,
	                         OpenAIStreamingService openAIStreamingService,
	                         RetrievalService retrievalService,
	                         ObjectMapper objectMapper,
	                         RestTemplateBuilder restTemplateBuilder,
	                         ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder.build();
    }

    /**
     * Serializes an object to JSON, throwing a RuntimeException on failure to ensure errors propagate.
     */
    private String jsonSerialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SSE data", e);
        }
    }

    /**
     * Creates a Flux containing a single SSE error event with safe JSON serialization.
     */
    private Flux<ServerSentEvent<String>> sseError(String message, String details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(new ErrorMessage(message, details));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE error: {}", message, e);
            json = "{\"message\":\"Error serialization failed\",\"details\":\"See server logs\"}";
        }
        return Flux.just(ServerSentEvent.<String>builder()
            .event(SSE_EVENT_ERROR)
            .data(json)
            .build());
    }

    /**
     * Normalizes the role from a chat turn for consistent comparison/display.
     */
    private String normalizeRole(ChatTurn turn) {
        return turn.getRole() == null
            ? ""
            : AsciiTextNormalizer.toLowerAscii(turn.getRole().trim());
    }


    /**
     * Streams a response to a user's chat message using Server-Sent Events (SSE).
     * Uses the OpenAI Java SDK for clean, reliable streaming without manual SSE parsing.
     *
     * @param body A JSON object containing the user's request. Expected format:
     *             <pre>{@code
     *               {
     *                 "sessionId": "some-session-id", // Optional, defaults to "default"
     *                 "latest": "The user's question?"   // The user's message
     *               }
     *             }</pre>
     * @return A {@link Flux} of strings representing the streaming response, sent as SSE data events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatStreamRequest request, HttpServletResponse response) {
        // Critical proxy headers for streaming
        response.addHeader("X-Accel-Buffering", "no"); // Nginx: disable proxy buffering
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        long requestToken = REQUEST_SEQUENCE.incrementAndGet();

        String sessionId = request.resolvedSessionId();
        String userQuery = request.userQuery();

        PIPELINE_LOG.info("[{}] ============================================", requestToken);
        PIPELINE_LOG.info("[{}] NEW CHAT REQUEST", requestToken);
        PIPELINE_LOG.info("[{}] ============================================", requestToken);
        
        List<Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        PIPELINE_LOG.info("[{}] Chat history loaded", requestToken);
        
        chatMemory.addUser(sessionId, userQuery);
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);

        // Build the complete prompt using existing ChatService logic
        // Pass model hint to optimize RAG for token-constrained models
        ChatService.ChatPromptOutcome promptOutcome =
            chatService.buildPromptWithContextOutcome(history, userQuery, ModelConfiguration.DEFAULT_MODEL);
        String fullPrompt = promptOutcome.prompt();
        
        // Use OpenAI streaming only (legacy fallback removed)
        if (openAIStreamingService.isAvailable()) {
            PIPELINE_LOG.info("[{}] Using OpenAI Java SDK for streaming", requestToken);
            
            // Clean OpenAI streaming - no manual SSE parsing, no token buffering artifacts
            // Use .share() to hot-share the stream and prevent double API subscription
            Flux<String> dataStream = openAIStreamingService.streamResponse(fullPrompt, TEMPERATURE)
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        chunkCount.incrementAndGet();
                    })
                    .onBackpressureBuffer(STREAM_BACKPRESSURE_BUFFER_SIZE)  // Bounded buffer to prevent unbounded memory growth
                    .share();  // Hot-share to prevent double subscription causing duplicate API calls

            // Heartbeats terminate when data stream completes (success or error).
            // Errors propagate through dataEvents to onErrorResume below - no duplicate logging here.
            Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                    .takeUntilOther(dataStream.ignoreElements())
                    .map(tick -> ServerSentEvent.<String>builder().comment(SSE_COMMENT_KEEPALIVE).build());

            Flux<ServerSentEvent<String>> statusEvents = Flux.fromIterable(promptOutcome.notices())
                    .map(notice -> ServerSentEvent.<String>builder()
                        .event(SSE_EVENT_STATUS)
                        .data(jsonSerialize(new StatusMessage(notice.summary(), notice.details())))
                        .build());

            // Wrap chunks in JSON to preserve whitespace - Spring's SSE handling can trim leading spaces
            // See: https://github.com/spring-projects/spring-framework/issues/27473
            Flux<ServerSentEvent<String>> dataEvents = dataStream
                    .map(chunk -> ServerSentEvent.<String>builder()
                            .event(SSE_EVENT_TEXT)
                            .data(jsonSerialize(new ChunkMessage(chunk)))
                            .build());

            return Flux.concat(statusEvents, Flux.merge(dataEvents, heartbeats))
                    .doOnComplete(() -> {
                        chatMemory.addAssistant(sessionId, fullResponse.toString());
                        PIPELINE_LOG.info("[{}] STREAMING COMPLETE", requestToken);
                    })
                    .onErrorResume(error -> {
                        // Log and send error event to client - errors must be communicated, not silently dropped
                        String errorDetail = buildUserFacingErrorMessage(error);
                        String diagnostics = error instanceof Exception exception
                            ? describeException(exception)
                            : error.toString();
                        PIPELINE_LOG.error("[{}] STREAMING ERROR: {}", requestToken, errorDetail, error);
                        return sseError(errorDetail, diagnostics);
                    });

        } else {
            // Service unavailable - send structured error event so client can handle appropriately
            PIPELINE_LOG.warn("[{}] OpenAI streaming service unavailable", requestToken);
            return sseError("Streaming service unavailable", "OpenAI streaming service is not ready");
        }
    }

    // Helper records for JSON serialization
    private record StatusMessage(String message, String details) {}
    private record ChunkMessage(String text) {}
    private record ErrorMessage(String message, String details) {}

    /**
     * Diagnostics: Return the RAG retrieval context for a given query.
     * Dev-only usage in UI; kept simple and safe.
     */
    @GetMapping("/diagnostics/retrieval")
    public RetrievalDiagnosticsResponse retrievalDiagnostics(@RequestParam("q") String query) {
        // Mirror token-constrained model constraints used in buildPromptWithContext
        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieveWithLimitOutcome(
            query,
            ModelConfiguration.RAG_LIMIT_CONSTRAINED,
            ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED
        );
        // Normalize URLs the same way as citations so we never emit file:// links
        List<Citation> citations = retrievalService.toCitations(outcome.documents());
        if (outcome.notices().isEmpty()) {
            return RetrievalDiagnosticsResponse.success(citations);
        }
        String noticeDetails = outcome.notices().stream()
            .map(notice -> notice.summary() + ": " + notice.details())
            .reduce((first, second) -> first + "; " + second)
            .orElse("Retrieval warnings present");
        return new RetrievalDiagnosticsResponse(citations, noticeDetails);
    }

    /**
     * Retrieves a list of relevant citations for a given query.
     *
     * @param query The search query string.
     * @return A {@link List} of {@link Citation} objects.
     */
    @GetMapping("/citations")
    public List<Citation> citations(@RequestParam("q") String query) {
        return chatService.citationsFor(query);
    }

    /**
     * Exports the last assistant message from a given chat session.
     *
     * @param sessionId The ID of the chat session (required).
     * @return The last assistant message or appropriate HTTP error.
     */
    @GetMapping(value = "/export/last", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportLast(@RequestParam(name = "sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("Session ID is required");
        }
        var turns = chatMemory.getTurns(sessionId);
        for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
            var turn = turns.get(turnIndex);
            if ("assistant".equals(normalizeRole(turn))) {
                return ResponseEntity.ok(turn.getText());
            }
        }
        return ResponseEntity.status(404).body("No assistant message found in session: " + sessionId);
    }

    /**
     * Exports the entire history of a chat session as a formatted string.
     *
     * @param sessionId The ID of the chat session (required).
     * @return The full conversation or appropriate HTTP error.
     */
    @GetMapping(value = "/export/session", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportSession(@RequestParam(name = "sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("Session ID is required");
        }
        var turns = chatMemory.getTurns(sessionId);
        if (turns.isEmpty()) {
            return ResponseEntity.status(404).body("No history found for session: " + sessionId);
        }
        StringBuilder formatted = new StringBuilder();
        for (var turn : turns) {
            String role = "user".equals(normalizeRole(turn)) ? "User" : "Assistant";
            formatted.append("### ").append(role).append("\n\n").append(turn.getText()).append("\n\n");
        }
        return ResponseEntity.ok(formatted.toString());
    }

    
    /**
     * Clears the chat history for a given session.
     *
     * @param sessionId The ID of the chat session. Defaults to "default".
     * @return A simple success message.
     */
    @PostMapping("/clear")
    public ResponseEntity<String> clearSession(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("No session ID provided");
        }
        chatMemory.clear(sessionId);
        PIPELINE_LOG.info("Cleared chat session");
	        return ResponseEntity.ok("Session cleared");
	    }
	    
	    /**
	     * Reports whether the configured local embedding server is reachable when the feature is enabled.
	     */
	    @GetMapping("/health/embeddings")
	    public ResponseEntity<EmbeddingsHealthResponse> checkEmbeddingsHealth() {
	        if (!localEmbeddingEnabled) {
	            return ResponseEntity.ok(EmbeddingsHealthResponse.disabled(localEmbeddingServerUrl));
	        }

        try {
            // Simple health check - try to get models list
            String healthUrl = localEmbeddingServerUrl + "/v1/models";
            restTemplate.getForEntity(healthUrl, String.class);
            return ResponseEntity.ok(EmbeddingsHealthResponse.healthy(localEmbeddingServerUrl));
        } catch (RestClientException httpError) {
            log.debug("Embedding server health check failed", httpError);
            String details = describeException(httpError);
            return ResponseEntity.ok(EmbeddingsHealthResponse.unhealthy(
                localEmbeddingServerUrl, "UNREACHABLE: " + details));
        }
    }

    /**
     * Builds a user-facing error message with provider context when possible.
     * Rate limit and IO errors include enough detail for users to understand which service failed.
     */
    private String buildUserFacingErrorMessage(Throwable error) {
        if (error instanceof RateLimitException rateLimitError) {
            // Extract provider from the exception message or headers if possible
            String message = rateLimitError.getMessage();
            if (message != null && message.contains("429")) {
                return "Rate limit reached - LLM provider returned 429. Please wait before retrying.";
            }
            return "Rate limit reached - " + error.getClass().getSimpleName();
        }

        if (error instanceof OpenAIIoException ioError) {
            Throwable cause = ioError.getCause();
            if (cause != null && cause.getMessage() != null
                    && cause.getMessage().toLowerCase().contains("interrupt")) {
                return "Request cancelled - LLM provider did not respond in time";
            }
            return "LLM provider connection failed - " + error.getClass().getSimpleName();
        }

        if (error instanceof IllegalStateException && error.getMessage() != null
                && error.getMessage().contains("providers unavailable")) {
            return error.getMessage();
        }

        // Default: include exception type for debugging
        return "Streaming error: " + error.getClass().getSimpleName();
    }
}
