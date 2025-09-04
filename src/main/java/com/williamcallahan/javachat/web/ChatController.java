package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.MarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/chat")
public class ChatController extends BaseController {
    @SuppressWarnings("unused") // Used by logging framework
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    
    private final ChatService chatService;
    private final ChatMemoryService chatMemory;
    private final MarkdownService markdownService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}")
    private String localEmbeddingServerUrl;

    @Value("${app.local-embedding.enabled:false}")
    private boolean localEmbeddingEnabled;

    public ChatController(ChatService chatService, ChatMemoryService chatMemory, 
                         MarkdownService markdownService, ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.markdownService = markdownService;
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
    public Flux<String> stream(@RequestBody Map<String, Object> body) {
        String requestId = "REQ-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();
        String sessionId = String.valueOf(body.getOrDefault("sessionId", "default"));
        String latest = String.valueOf(body.getOrDefault("latest", ""));
        
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        PIPELINE_LOG.info("[{}] NEW CHAT REQUEST - Session: {}", requestId, sessionId);
        PIPELINE_LOG.info("[{}] User query: {}", requestId, latest);
        PIPELINE_LOG.info("[{}] ============================================", requestId);
        
        List<Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        PIPELINE_LOG.info("[{}] Chat history: {} previous messages", requestId, history.size());
        
        chatMemory.addUser(sessionId, latest);
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);
        
        return chatService.streamAnswer(history, latest)
                .map(chunk -> {
                    buffer.append(chunk);
                    fullResponse.append(chunk);
                    
                    String buffered = buffer.toString();
                    
                    // CRITICAL: Check if we're inside a code block - don't break if so!
                    int openFences = countOccurrences(buffered, "```");
                    boolean insideCodeBlock = (openFences % 2) == 1; // Odd count means we're inside
                    
                    if (insideCodeBlock) {
                        // We're inside a code block - keep buffering until we close it
                        return "";
                    }
                    
                    // Check for natural break points: sentence ends, newlines, list markers, or code block end
                    boolean hasBreakPoint = buffered.endsWith("```\n") || // Code block just ended
                                           buffered.matches(".*[.!?]\\s*$") || 
                                           buffered.endsWith("\n\n") || // Paragraph break
                                           buffered.matches(".*\\d+\\.\\s+\\S.*") || // list marker followed by visible content
                                           buffered.contains("- ") ||
                                           buffer.length() > 500;  // Force break for very long chunks
                    
                    if (hasBreakPoint) {
                        // IMPORTANT: Don't preprocess during streaming!
                        // The client will call /api/markdown/render which does full preprocessing.
                        // Double preprocessing causes corruption of markdown structures.
                        String toSend = buffered;
                        buffer.setLength(0);  // Clear buffer
                        
                        PIPELINE_LOG.info("[{}] SENT CHUNK: '{}'",
                                requestId, toSend.replace("\n", "\\n"));

                        return toSend;
                    } else {
                        // Keep buffering
                        return "";
                    }
                })
                .filter(s -> !s.isEmpty())  // Only send non-empty processed chunks
                .doOnNext(chunk -> {
                    int count = chunkCount.incrementAndGet();
                    if (count % 10 == 0) {
                        PIPELINE_LOG.debug("[{}] Streaming processed chunk #{}: {} chars total", 
                            requestId, count, fullResponse.length());
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // Send any remaining buffered content (without preprocessing)
                    if (buffer.length() > 0) {
                        String remaining = buffer.toString();
                        PIPELINE_LOG.info("[{}] SENT FINAL CHUNK: '{}'",
                                requestId, remaining.replace("\n", "\\n"));
                        return Flux.just(remaining);
                    }
                    return Flux.empty();
                }))
                .doOnComplete(() -> {
                    // Store the full preprocessed response in memory
                    String processed = markdownService.preprocessMarkdown(fullResponse.toString());
                    chatMemory.addAssistant(sessionId, processed);
                    PIPELINE_LOG.info("[{}] STREAMING COMPLETE - {} chunks, {} total chars", 
                        requestId, chunkCount.get(), processed.length());
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
    public String exportLast(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
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
    public String exportSession(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        var history = chatMemory.getTurns(sessionId);
        StringBuilder sb = new StringBuilder();
        for (var t : history) {
            String role = t.getRole().equalsIgnoreCase("user") ? "User" : "Assistant";
            sb.append("### ").append(role).append("\n\n").append(t.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private int countOccurrences(String str, String substr) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(substr, idx)) != -1) {
            count++;
            idx += substr.length();
        }
        return count;
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
}

