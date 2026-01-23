package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.RetrievalService;
import org.springframework.ai.document.Document;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import com.williamcallahan.javachat.service.markdown.ProcessedMarkdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes chat endpoints for streaming responses, session history management, and diagnostics.
 */
@RestController
@RequestMapping("/api/chat")
@PermitAll
public class ChatController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    
    private final ChatService chatService;
    private final ChatMemoryService chatMemory;
    private final UnifiedMarkdownService unifiedMarkdownService;
    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    // Deprecated stream processor removed from active use; unified AST processing handles markdown.
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}")
    private String localEmbeddingServerUrl;

	    @Value("${app.local-embedding.enabled:false}")
	    private boolean localEmbeddingEnabled;

	    /**
	     * Creates the chat controller wired to chat, retrieval, and markdown services.
	     *
	     * @param chatService chat orchestration service
	     * @param chatMemory conversation memory service
	     * @param unifiedMarkdownService unified markdown renderer
	     * @param openAIStreamingService streaming LLM client
	     * @param retrievalService retrieval service for diagnostics
	     * @param exceptionBuilder shared exception response builder
	     */
	    public ChatController(ChatService chatService, ChatMemoryService chatMemory,
	                         UnifiedMarkdownService unifiedMarkdownService,
	                         OpenAIStreamingService openAIStreamingService,
	                         RetrievalService retrievalService,
	                         ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.unifiedMarkdownService = unifiedMarkdownService;
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
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
        // Pass model hint to optimize RAG for GPT-5's 8K token input limit
        String fullPrompt = chatService.buildPromptWithContext(history, userQuery, "gpt-5");
        
        // Use OpenAI streaming only (legacy fallback removed)
        if (openAIStreamingService.isAvailable()) {
            PIPELINE_LOG.info("[{}] Using OpenAI Java SDK for streaming", requestToken);
            
            // Clean OpenAI streaming - no manual SSE parsing, no token buffering artifacts
            // Use .share() to hot-share the stream and prevent double API subscription
            Flux<String> dataStream = openAIStreamingService.streamResponse(fullPrompt, 0.7)
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        chunkCount.incrementAndGet();
                    })
                    .onBackpressureLatest()  // Handle backpressure to prevent memory buildup
                    .share();  // Hot-share to prevent double subscription causing duplicate API calls

            // Heartbeats terminate when data stream completes (success or error).
            // Errors propagate through dataEvents to onErrorResume below - no duplicate logging here.
            Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(20))
                    .takeUntilOther(dataStream.ignoreElements())
                    .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build());

            Flux<ServerSentEvent<String>> dataEvents = dataStream
                    .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());

            return Flux.merge(dataEvents, heartbeats)
                    .doOnComplete(() -> {
                        // Store the full response using AST-based processing
                        ProcessedMarkdown processedResult = unifiedMarkdownService.process(fullResponse.toString());
                        String processed = processedResult.html();
                        chatMemory.addAssistant(sessionId, processed);
                        PIPELINE_LOG.info("[{}] STREAMING COMPLETE", requestToken);
                    })
                    .onErrorResume(error -> {
                        // Log and send error event to client - errors must be communicated, not silently dropped
                        PIPELINE_LOG.error("[{}] STREAMING ERROR", requestToken);
                        return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("Streaming error: " + error.getClass().getSimpleName())
                            .build());
                    });

        } else {
            // Service unavailable - send structured error event so client can handle appropriately
            PIPELINE_LOG.warn("[{}] OpenAI streaming service unavailable", requestToken);
            return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data("Service temporarily unavailable. The streaming service is not ready.")
                .build());
        }
    }

    /**
     * Diagnostics: Return the RAG retrieval context for a given query.
     * Dev-only usage in UI; kept simple and safe.
     */
    @GetMapping("/diagnostics/retrieval")
    public RetrievalDiagnosticsResponse retrievalDiagnostics(@RequestParam("q") String query) {
        // Mirror GPT-5 constraints used in buildPromptWithContext
        List<Document> documents = retrievalService.retrieveWithLimit(query, 3, 600);
        // Normalize URLs the same way as citations so we never emit file:// links
        List<Citation> citations = retrievalService.toCitations(documents);
        return RetrievalDiagnosticsResponse.success(citations);
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
            String normalizedRole = turn.getRole() == null
                ? ""
                : turn.getRole().trim().toLowerCase(Locale.ROOT);
            if ("assistant".equals(normalizedRole)) {
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
            String normalizedRole = turn.getRole() == null
                ? ""
                : turn.getRole().trim().toLowerCase(Locale.ROOT);
            String role = "user".equals(normalizedRole) ? "User" : "Assistant";
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
        } catch (Exception healthCheckError) {
            return ResponseEntity.ok(EmbeddingsHealthResponse.unhealthy(
                localEmbeddingServerUrl, healthCheckError.getClass().getSimpleName()));
        }
    }
    
    /**
     * Processes text using the new AST-based markdown processing.
     * This endpoint provides structured output with better list formatting and Unicode bullet support.
     *
     * @param request The request containing text to process
     * @return ProcessedMarkdown with structured citations and enrichments
     * @deprecated Scheduled for removal; use unified processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @PostMapping("/process-structured")
    public ResponseEntity<ProcessedMarkdown> processStructured(@RequestBody ProcessStructuredRequest request) {
        String text = request.text();
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            ProcessedMarkdown processed = unifiedMarkdownService.process(text);
            PIPELINE_LOG.info("Processed text with AST-based service: {} citations, {} enrichments",
                             processed.citations().size(), processed.enrichments().size());
            return ResponseEntity.ok(processed);
        } catch (Exception processingError) {
            log.error("Error processing structured markdown", processingError);
            return ResponseEntity.internalServerError().build();
        }
    }
}
