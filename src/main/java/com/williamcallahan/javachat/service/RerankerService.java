package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RerankerService {
    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public RerankerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public List<Document> rerank(String query, List<Document> docs, int returnK) {
        if (docs.size() <= 1) return docs;
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a re-ranker. Reorder the following documents for the query by relevance. Return JSON: {\"order\":[indices...]} with 0-based indices.\n");
            prompt.append("Query: ").append(query).append("\n\n");
            for (int i = 0; i < docs.size(); i++) {
                var d = docs.get(i);
                prompt.append("["+i+"] ").append(d.getMetadata().get("title")).append(" | ")
                      .append(d.getMetadata().get("url")).append("\n")
                      .append(trim(d.getText(), 500)).append("\n\n");
            }
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
            log.info("Falling back to original document order due to reranking error");
            return docs.subList(0, Math.min(returnK, docs.size()));
        }
    }

    private String trim(String s, int len) { return s.length() <= len ? s : s.substring(0, len) + "…"; }
}





