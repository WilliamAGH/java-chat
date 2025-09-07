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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/chat")
public class ChatController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    
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
    public Flux<ServerSentEvent<String>> stream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        // Critical proxy headers for streaming
        response.addHeader("X-Accel-Buffering", "no"); // Nginx: disable proxy buffering
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        String requestId = "REQ-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();
        
        // Generate session ID if not provided using same logic as frontend
        String sessionId = body.get("sessionId") != null 
            ? String.valueOf(body.get("sessionId"))
            : "chat-" + System.currentTimeMillis() + "-" + Math.random();
        // Support both "message" (from curl/API) and "latest" (from web UI) field names
        String latest = String.valueOf(body.getOrDefault("message", body.getOrDefault("latest", "")));
        
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        PIPELINE_LOG.info("[{}] NEW CHAT REQUEST - Session: {}", requestId, sessionId);
        PIPELINE_LOG.info("[{}] User query: {}", requestId, latest);
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        
        List<Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        PIPELINE_LOG.info("[{}] Chat history: {} previous messages", requestId, history.size());
        
        chatMemory.addUser(sessionId, latest);
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);
        
        // Build the complete prompt using existing ChatService logic
        // Pass model hint to optimize RAG for GPT-5's 8K token input limit
        String fullPrompt = chatService.buildPromptWithContext(history, latest, "gpt-5");
        
        // Use OpenAI streaming only (legacy fallback removed)
        if (openAIStreamingService.isAvailable()) {
            PIPELINE_LOG.info("[{}] Using OpenAI Java SDK for streaming", requestId);
            
            // Clean OpenAI streaming - no manual SSE parsing, no token buffering artifacts
            Flux<String> dataStream = openAIStreamingService.streamResponse(fullPrompt, 0.7)
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        chunkCount.incrementAndGet();
                    })
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .onBackpressureLatest();  // Handle backpressure to prevent memory buildup

            // Heartbeats should stop when the data stream completes to allow the SSE connection
            // to close cleanly. Otherwise, an infinite heartbeat Flux would keep the stream open.
            Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(20))
                    .takeUntilOther(dataStream.ignoreElements().onErrorResume(e -> Mono.empty()))
                    .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build());

            Flux<ServerSentEvent<String>> dataEvents = dataStream
                    .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());

            return Flux.merge(dataEvents, heartbeats)
                    .doOnComplete(() -> {
                        // Store the full response using AST-based processing
                        ProcessedMarkdown processedResult = unifiedMarkdownService.process(fullResponse.toString());
                        String processed = processedResult.html();
                        chatMemory.addAssistant(sessionId, processed);
                        PIPELINE_LOG.info("[{}] STREAMING COMPLETE - {} chunks, {} total chars, {} citations, {} enrichments", 
                            requestId, chunkCount.get(), processed.length(), 
                            processedResult.citations().size(), processedResult.enrichments().size());
                    })
                    .doOnError(error -> {
                        PIPELINE_LOG.error("[{}] FINAL STREAMING ERROR: {}", requestId, error.getMessage());
                    });
                    
        } else {
            // If SDK unavailable, return minimal message
            return Flux.just(ServerSentEvent.<String>builder().data("Service temporarily unavailable. Try again shortly.").build());
        }
    }

    /**
     * Diagnostics: Return the RAG retrieval context for a given query.
     * Dev-only usage in UI; kept simple and safe.
     */
    @GetMapping("/diagnostics/retrieval")
    public Map<String, Object> retrievalDiagnostics(@RequestParam("q") String q) {
        try {
            // Mirror GPT-5 constraints used in buildPromptWithContext
            List<Document> docs = retrievalService.retrieveWithLimit(q, 3, 600);
            // Normalize URLs the same way as citations so we never emit file:// links
            List<Citation> citations = retrievalService.toCitations(docs);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Citation c : citations) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("url", c.getUrl());
                m.put("title", c.getTitle());
                m.put("snippet", c.getSnippet());
                out.add(m);
            }
            return Map.of("docs", out);
        } catch (Exception e) {
            log.warn("retrieval diagnostics error: {}", e.toString());
            return Map.of("docs", List.of(), "error", "unavailable");
        }
    }

    /**
     * Retrieves a list of relevant citations for a given query.
     *
     * @param q The search query string.
     * @return A {@link List} of {@link Citation} objects.
     */
    @GetMapping("/citations")
    public List<Citation> citations(@RequestParam("q") String q) {
        return chatService.citationsFor(q);
    }

    /**
     * Exports the last assistant message from a given chat session.
     *
     * @param sessionId The ID of the chat session. Defaults to "default".
     * @return A plain text string of the last assistant message.
     */
    @GetMapping(value = "/export/last", produces = MediaType.TEXT_PLAIN_VALUE)
    public String exportLast(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "No session ID provided";
        }
        var turns = chatMemory.getTurns(sessionId);
        for (int i = turns.size() - 1; i >= 0; i--) {
            var t = turns.get(i);
            if ("assistant".equalsIgnoreCase(t.getRole())) return t.getText();
        }
        return "";
    }

    /**
     * Exports the entire history of a chat session as a formatted string.
     *
     * @param sessionId The ID of the chat session. Defaults to "default".
     * @return A plain text string representing the full conversation.
     */
    @GetMapping(value = "/export/session", produces = MediaType.TEXT_PLAIN_VALUE)
    public String exportSession(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "No session ID provided";
        }
        var history = chatMemory.getTurns(sessionId);
        StringBuilder sb = new StringBuilder();
        for (var t : history) {
            String role = t.getRole().equalsIgnoreCase("user") ? "User" : "Assistant";
            sb.append("### ").append(role).append("\n\n").append(t.getText()).append("\n\n");
        }
        return sb.toString();
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
        PIPELINE_LOG.info("Cleared chat session: {}", sessionId);
        return ResponseEntity.ok("Session cleared");
    }
    
    @GetMapping("/health/embeddings")
    public ResponseEntity<Map<String, Object>> checkEmbeddingsHealth() {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("localEmbeddingEnabled", localEmbeddingEnabled);
        response.put("serverUrl", localEmbeddingServerUrl);

        if (localEmbeddingEnabled) {
            try {
                // Simple health check - try to get models list
                String healthUrl = localEmbeddingServerUrl + "/v1/models";
                restTemplate.getForEntity(healthUrl, String.class);
                response.put("status", "healthy");
                response.put("serverReachable", true);
            } catch (Exception e) {
                response.put("status", "unhealthy");
                response.put("serverReachable", false);
                response.put("error", e.getMessage());
            }
        } else {
            response.put("status", "disabled");
            response.put("serverReachable", null);
        }

        return ResponseEntity.ok(response);
    }
    
    /**
     * Processes text using the new AST-based markdown processing.
     * This endpoint provides structured output with better list formatting and Unicode bullet support.
     * 
     * @param body JSON object containing the text to process
     * @return ProcessedMarkdown with structured citations and enrichments
     */
    @Deprecated(since = "1.0", forRemoval = true)
    @PostMapping("/process-structured")
    public ResponseEntity<ProcessedMarkdown> processStructured(@RequestBody Map<String, String> body) {
        try {
            String text = body.get("text");
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            
            ProcessedMarkdown result = unifiedMarkdownService.process(text);
            PIPELINE_LOG.info("Processed text with AST-based service: {} citations, {} enrichments", 
                             result.citations().size(), result.enrichments().size());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error processing structured markdown", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

