package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RerankerService {
    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    private ChatClient chatClient;
    private ObjectMapper mapper = new ObjectMapper();

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_MODEL:gpt-4o-mini}")
    private String openaiModel;

    private final WebClient webClient;

    public RerankerService(ChatClient chatClient, WebClient.Builder webClientBuilder) {
        this.chatClient = chatClient;
        this.webClient = webClientBuilder.build();
    }

    public List<Document> rerank(String query, List<Document> docs, int returnK) {
        if (docs.size() <= 1) return docs;
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a re-ranker. Reorder the following documents for the query by relevance. Return JSON: {\"order\":[indices...]} with 0-based indices.\n");
        prompt.append("Query: ").append(query).append("\n\n");
        for (int i = 0; i < docs.size(); i++) {
            var d = docs.get(i);
            prompt.append("["+i+"] ").append(d.getMetadata().get("title")).append(" | ")
                  .append(d.getMetadata().get("url")).append("\n")
                  .append(trim(d.getText(), 500)).append("\n\n");
        }
        
        try {
            String response = chatClient.prompt().user(prompt.toString()).call().content();
            // Clean up response - remove markdown code blocks if present
            String json = response;
            if (json.contains("```")) {
                // Extract JSON from markdown code block
                int start = json.indexOf("```");
                if (start >= 0) {
                    start = json.indexOf("\n", start) + 1; // Skip the ```json line
                    int end = json.indexOf("```", start);
                    if (end > start) {
                        json = json.substring(start, end).trim();
                    }
                }
            }
            // Also handle case where response starts with backticks
            json = json.replaceAll("^`+|`+$", "").trim();
            if (json.startsWith("json")) {
                json = json.substring(4).trim();
            }
            
            JsonNode root = mapper.readTree(json);
            List<Document> reordered = new ArrayList<>();
            if (root.has("order") && root.get("order").isArray()) {
                for (JsonNode n : root.get("order")) {
                    int idx = n.asInt();
                    if (idx >= 0 && idx < docs.size()) reordered.add(docs.get(idx));
                }
            }
            if (reordered.isEmpty()) {
                log.warn("Reranking produced empty results, falling back to original order");
                return docs.subList(0, Math.min(returnK, docs.size()));
            }
            log.debug("Successfully reranked {} documents", reordered.size());
            return reordered.subList(0, Math.min(returnK, reordered.size()));
        } catch (Exception e) {
            log.error("Error during reranking: {}", e.getMessage(), e);
            if (e.getMessage() != null && e.getMessage().contains("429") && openaiApiKey != null && !openaiApiKey.isBlank()) {
                log.info("Rate limit hit - attempting OpenAI fallback for reranking");
                try {
                    String fallbackResponse = webClient.post()
                        .uri("https://api.openai.com/v1/chat/completions")
                        .header("Authorization", "Bearer " + openaiApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                            "model", openaiModel,
                            "messages", List.of(Map.of("role", "user", "content", prompt.toString())),
                            "temperature", 0.0
                        ))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                    // Parse fallbackResponse (adapt from lines 36-69)
                    String json = fallbackResponse;
                    if (json.contains("```")) {
                        int start = json.indexOf("```");
                        if (start >= 0) {
                            start = json.indexOf("\n", start) + 1;
                            int end = json.indexOf("```", start);
                            if (end > start) {
                                json = json.substring(start, end).trim();
                            }
                        }
                    }
                    json = json.replaceAll("^`+|`+$", "").trim();
                    if (json.startsWith("json")) {
                        json = json.substring(4).trim();
                    }

                    JsonNode root = mapper.readTree(json);
                    List<Document> reordered = new ArrayList<>();
                    if (root.has("order") && root.get("order").isArray()) {
                        for (JsonNode n : root.get("order")) {
                            int idx = n.asInt();
                            if (idx >= 0 && idx < docs.size()) reordered.add(docs.get(idx));
                        }
                    }
                    if (reordered.isEmpty()) {
                        log.warn("Fallback reranking produced empty results, falling back to original order");
                        return docs.subList(0, Math.min(returnK, docs.size()));
                    }
                    log.debug("Successfully fallback reranked {} documents", reordered.size());
                    return reordered.subList(0, Math.min(returnK, reordered.size()));
                } catch (Exception fallbackEx) {
                    log.error("Fallback reranking failed", fallbackEx);
                }
            }
            log.info("Falling back to original document order due to reranking error");
            return docs.subList(0, Math.min(returnK, docs.size()));
        }
    }

    private String trim(String s, int len) { return s.length() <= len ? s : s.substring(0, len) + "…"; }
}





