package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Reorders retrieved documents using an LLM-driven relevance ranking.
 */
@Service
public class RerankerService {

	    private static final Logger log = LoggerFactory.getLogger(
	        RerankerService.class
	    );
    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper mapper;

    /**
     * Creates a reranker backed by the streaming LLM client.
     *
     * @param openAIStreamingService streaming LLM client
     * @param objectMapper Jackson object mapper
     */
    public RerankerService(OpenAIStreamingService openAIStreamingService, ObjectMapper objectMapper) {
        this.openAIStreamingService = openAIStreamingService;
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    /**
     * Rerank documents by relevance to query using LLM.
     * Cache key includes document URLs to prevent returning results for wrong document sets.
     */
    @Cacheable(
        value = "reranker-cache",
        key = "#query + ':' + T(com.williamcallahan.javachat.service.RerankerService).computeDocsHash(#docs) + ':' + #returnK"
    )
    public List<Document> rerank(
        String query,
        List<Document> docs,
        int returnK
    ) {
        if (docs.size() <= 1) {
            return docs;
        }

        log.debug("Reranking {} documents", docs.size());

        try {
            String response = callLlmForReranking(query, docs);
            if (response == null || response.isBlank()) {
                return limitDocs(docs, returnK);
            }

            List<Document> reordered = parseRerankResponse(response, docs);
            if (reordered.isEmpty()) {
                log.warn(
                    "Reranking produced empty results, falling back to original order"
                );
                return limitDocs(docs, returnK);
            }

            log.debug("Successfully reranked {} documents", reordered.size());
            return limitDocs(reordered, returnK);
        } catch (JsonProcessingException exception) {
            log.error("Reranking failed to parse response; using original document order", exception);
            return limitDocs(docs, returnK);
        } catch (RuntimeException exception) {
            log.error("Reranking failed; using original document order", exception);
            return limitDocs(docs, returnK);
        }
    }

    /**
     * Call the LLM service to get reranking order.
     * Returns null if service unavailable or times out.
     */
    private String callLlmForReranking(String query, List<Document> docs) {
        if (
            openAIStreamingService == null ||
            !openAIStreamingService.isAvailable()
        ) {
            log.warn("OpenAIStreamingService unavailable; skipping LLM rerank");
            return null;
        }

        String prompt = buildRerankPrompt(query, docs);

        // Cap reranker latency aggressively; fall back on original order fast
        return openAIStreamingService
            .complete(prompt, 0.0)
            .timeout(java.time.Duration.ofSeconds(4))
            .onErrorResume(error -> {
                log.debug(
                    "Reranker LLM call short-circuited (exception type: {})",
                    error.getClass().getSimpleName()
                );
                return reactor.core.publisher.Mono.empty();
            })
            .blockOptional()
            .orElse(null);
    }

    /**
     * Build the prompt for the reranking LLM call.
     */
    private String buildRerankPrompt(String query, List<Document> docs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(
            "You are a document re-ranker for the Java learning assistant system.\n"
        );
        prompt.append(
            "Reorder the following documents by relevance to the query.\n"
        );
        prompt.append(
            "Consider Java-specific context, version relevance, and learning value.\n"
        );
        prompt.append(
            "Return JSON: {\"order\":[indices...]} with 0-based indices.\n\n"
        );
	        prompt.append("Query: ").append(query).append("\n\n");

        for (int docIndex = 0; docIndex < docs.size(); docIndex++) {
            Document document = docs.get(docIndex);
            Map<String, Object> metadata = document.getMetadata();
            Object titleValue = metadata.get("title");
            Object urlValue = metadata.get("url");
            String title = titleValue == null ? "" : String.valueOf(titleValue);
            String url = urlValue == null ? "" : String.valueOf(urlValue);
            String text = document.getText();
	            prompt
	                .append("[")
	                .append(docIndex)
                .append("] ")
                .append(title)
                .append(" | ")
                .append(url)
                .append("\n")
                .append(trim(text == null ? "" : text, 500))
                .append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * Parse the LLM response to extract document ordering.
     */
    private List<Document> parseRerankResponse(
        String response,
        List<Document> docs
    ) throws JsonProcessingException {
        String json = extractJsonFromResponse(response);
        JsonNode root = mapper.readTree(json);

        List<Document> reordered = new ArrayList<>();
        if (root.has("order") && root.get("order").isArray()) {
            for (JsonNode orderNode : root.get("order")) {
                int documentIndex = orderNode.asInt();
                if (documentIndex >= 0 && documentIndex < docs.size()) {
                    reordered.add(docs.get(documentIndex));
                }
            }
        }
        return reordered;
    }

    /**
     * Extract JSON from LLM response, handling markdown code blocks.
     */
    private String extractJsonFromResponse(String response) {
        String json = response;

        // Extract from markdown code block if present
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

        // Handle response starting with backticks or "json" prefix
        json = json.replaceAll("^`+|`+$", "").trim();
        if (json.startsWith("json")) {
            json = json.substring(4).trim();
        }

        return json;
    }

    /**
     * Limit document list to returnK elements.
     */
    private List<Document> limitDocs(List<Document> docs, int returnK) {
        return docs.subList(0, Math.min(returnK, docs.size()));
    }

    private String trim(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    /**
     * Compute a stable hash of documents for cache key.
     * Uses URLs as document identity since they are unique in the context of reranking.
     */
    public static String computeDocsHash(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "empty";
        }
        StringBuilder hashBuilder = new StringBuilder();
        for (Document doc : docs) {
            Object url = doc.getMetadata().get("url");
            String text = doc.getText();
            hashBuilder.append(url != null ? url.toString() : (text != null ? text.hashCode() : 0));
            hashBuilder.append("|");
        }
        return Integer.toHexString(hashBuilder.toString().hashCode());
    }
}
