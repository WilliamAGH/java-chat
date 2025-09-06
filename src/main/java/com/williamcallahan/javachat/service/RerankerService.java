package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RerankerService {
    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RerankerService(OpenAIStreamingService openAIStreamingService) {
        this.openAIStreamingService = openAIStreamingService;
    }

    @Cacheable(value = "reranker-cache", key = "#query + ':' + #docs.size() + ':' + #returnK")
    public List<Document> rerank(String query, List<Document> docs, int returnK) {
        if (docs.size() <= 1) return docs;
        
        log.debug("Reranking {} documents for query: {}", docs.size(), query);
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document re-ranker for the Java learning assistant system.\n");
        prompt.append("Reorder the following documents by relevance to the query.\n");
        prompt.append("Consider Java-specific context, version relevance, and learning value.\n");
        prompt.append("Return JSON: {\"order\":[indices...]} with 0-based indices.\n\n");
        prompt.append("Query: ").append(query).append("\n\n");
        for (int i = 0; i < docs.size(); i++) {
            var d = docs.get(i);
            prompt.append("["+i+"] ").append(d.getMetadata().get("title")).append(" | ")
                  .append(d.getMetadata().get("url")).append("\n")
                  .append(trim(d.getText(), 500)).append("\n\n");
        }
        
        try {
            String response;
            if (openAIStreamingService != null && openAIStreamingService.isAvailable()) {
                // Cap reranker latency aggressively; fall back on original order fast
                response = openAIStreamingService
                        .complete(prompt.toString(), 0.0)
                        .timeout(java.time.Duration.ofSeconds(4))
                        .onErrorResume(e -> {
                            log.debug("Reranker LLM call short-circuited: {}", e.toString());
                            return reactor.core.publisher.Mono.empty();
                        })
                        .blockOptional()
                        .orElse(null);
                if (response == null || response.isBlank()) {
                    return docs.subList(0, Math.min(returnK, docs.size()));
                }
            } else {
                log.warn("OpenAIStreamingService unavailable; skipping LLM rerank and returning original order");
                return docs.subList(0, Math.min(returnK, docs.size()));
            }
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
            log.error("Reranking failed, using original document order", e);
            return docs.subList(0, Math.min(returnK, docs.size()));
        }
    }

    private String trim(String s, int len) { 
        return s.length() <= len ? s : s.substring(0, len) + "…"; 
    }
}
