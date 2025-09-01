package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatMemoryService chatMemory;

    public ChatController(ChatService chatService, ChatMemoryService chatMemory) {
        this.chatService = chatService;
        this.chatMemory = chatMemory;
    }

    /**
     * Stream answer with SSE. Body: { "sessionId": "s1", "latest": "question" }
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", "default"));
        String latest = String.valueOf(body.getOrDefault("latest", ""));
        List<Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        chatMemory.addUser(sessionId, latest);
        StringBuilder sb = new StringBuilder();
        return chatService.streamAnswer(history, latest)
                .doOnNext(sb::append)
                .doOnComplete(() -> chatMemory.addAssistant(sessionId, sb.toString()));
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
}


