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
     * Stream answer with SSE. Body: { "sessionId": "s1", "latest": "question" }
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
        StringBuilder sb = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);
        
        return chatService.streamAnswer(history, latest)
                .map(chunk -> {
                    // CRITICAL: Preserve newlines in chunks!
                    // The LLM sends text that may have lost formatting
                    // We need to ensure chunks maintain structure
                    return chunk;
                })
                .doOnNext(chunk -> {
                    sb.append(chunk);
                    int count = chunkCount.incrementAndGet();
                    if (count % 10 == 0) {
                        PIPELINE_LOG.debug("[{}] Streaming chunk #{}: {} chars total", 
                            requestId, count, sb.length());
                    }
                })
                .doOnComplete(() -> {
                    // Process the complete message with markdown formatting
                    String rawResponse = sb.toString();
                    // Apply markdown processing to fix formatting issues
                    String processed = markdownService.preprocessMarkdown(rawResponse);
                    chatMemory.addAssistant(sessionId, processed);
                    PIPELINE_LOG.info("[{}] STREAMING COMPLETE - {} chunks, {} total chars", 
                        requestId, chunkCount.get(), processed.length());
                })
                .doOnError(error -> {
                    PIPELINE_LOG.error("[{}] STREAMING ERROR: {}", requestId, error.getMessage());
                });
    }

    /**
     * Return citations for a query.
     */
    @GetMapping("/citations")
    public List<Citation> citations(@RequestParam("q") String q) {
        return chatService.citationsFor(q);
    }

    @GetMapping(value = "/export/last", produces = MediaType.TEXT_PLAIN_VALUE)
    public String exportLast(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        var turns = chatMemory.getTurns(sessionId);
        for (int i = turns.size() - 1; i >= 0; i--) {
            var t = turns.get(i);
            if ("assistant".equalsIgnoreCase(t.getRole())) return t.getText();
        }
        return "";
    }

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


