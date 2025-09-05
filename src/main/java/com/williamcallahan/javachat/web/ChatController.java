package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
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
    // Deprecated stream processor removed from active use; unified AST processing handles markdown.
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}")
    private String localEmbeddingServerUrl;

    @Value("${app.local-embedding.enabled:false}")
    private boolean localEmbeddingEnabled;

    public ChatController(ChatService chatService, ChatMemoryService chatMemory,
                         UnifiedMarkdownService unifiedMarkdownService,
                         ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.unifiedMarkdownService = unifiedMarkdownService;
    }

    // Normalize token joining to prevent artifacts like "worddata:" or space-before-punctuation
    private String normalizeDelta(String delta, StringBuilder full) {
        if (delta == null || delta.isEmpty()) return "";
        String d = delta;
        char prev = full.length() > 0 ? full.charAt(full.length() - 1) : '\0';
        // Remove space before punctuation
        if (d.length() > 0 && 
            (d.charAt(0) == '.' || d.charAt(0) == ',' || d.charAt(0) == '!' || d.charAt(0) == '?' || d.charAt(0) == ';' || d.charAt(0) == ':')) {
            if (full.length() > 0 && full.charAt(full.length() - 1) == ' ') {
                full.setLength(full.length() - 1);
            }
        }
        // Remove space before apostrophe contractions
        if (d.startsWith("'") && full.length() > 0 && Character.isLetterOrDigit(prev)) {
            if (full.charAt(full.length() - 1) == ' ') {
                full.setLength(full.length() - 1);
            }
        }
        return d;
    }

    /**
     * Streams a response to a user's chat message using Server-Sent Events (SSE).
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
    public Flux<String> stream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
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
        
        // Create heartbeat stream for keeping connections alive through proxies
        Flux<String> heartbeats = Flux.interval(Duration.ofSeconds(20))
                .map(i -> ": keepalive\n\n");  // SSE comment format

        // Main data stream - buffer small tokens to avoid flooding with SSE events
        Flux<String> dataStream = chatService.streamAnswer(history, latest)
                .bufferTimeout(10, Duration.ofMillis(100))  // Buffer up to 10 tokens or 100ms timeout
                .filter(chunks -> !chunks.isEmpty())  // Skip empty buffers
                .map(chunks -> {
                    // Combine all chunks in this buffer
                    StringBuilder buffer = new StringBuilder();
                    for (String chunk : chunks) {
                        String normalized = normalizeDelta(chunk, fullResponse);
                        fullResponse.append(normalized);
                        buffer.append(normalized);
                        chunkCount.incrementAndGet();
                    }
                    
                    String combined = buffer.toString();
                    if (combined.isEmpty()) {
                        return "";  // Will be filtered out
                    }
                    
                    // MDN SSE: an event is a block separated by a blank line; use only data: lines
                    // Ensure no accidental CR characters get through
                    String payload = combined.replace("\r", "");
                    // Prefix each line with "data: " per SSE spec so proxies/clients don't mangle multi-line payloads
                    String perLine = payload.replace("\n", "\ndata: ");
                    return "data: " + perLine + "\n\n";
                })
                .filter(event -> !event.isEmpty())  // Remove empty events
                .concatWith(Flux.defer(() -> {
                    // Send any remaining buffered content 
                    return Flux.empty(); // No additional final content needed
                }))
                .onBackpressureLatest();  // Handle backpressure to prevent memory buildup

        // Append terminal event and merge with heartbeats; complete stream after [DONE]
        Flux<String> framed = dataStream.concatWith(reactor.core.publisher.Mono.just("event: done\ndata: [DONE]\n\n"));
        return Flux.merge(framed, heartbeats)
                .takeUntil(s -> s.contains("[DONE]"))
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
                    PIPELINE_LOG.error("[{}] STREAMING ERROR: {}", requestId, error.getMessage());
                });
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

