package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    /** Markdown code fence delimiter used when parsing fenced LLM output. */
    private static final String CODE_FENCE_MARKER = "```";

    /** Maximum character length of document text included in the rerank prompt. */
    private static final int RERANK_PROMPT_TEXT_MAX_LENGTH = 500;

    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper mapper;
    private final Duration rerankerTimeout;

    /**
     * Creates a reranker backed by the streaming LLM client.
     *
     * @param openAIStreamingService streaming LLM client
     * @param objectMapper Jackson object mapper
     * @param appProperties application configuration containing reranker timeout budget
     */
    public RerankerService(
            OpenAIStreamingService openAIStreamingService, ObjectMapper objectMapper, AppProperties appProperties) {
        this.openAIStreamingService = openAIStreamingService;
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.rerankerTimeout =
                Objects.requireNonNull(appProperties, "appProperties").getRag().getRerankerTimeout();
    }

    /**
     * Rerank documents by relevance to query using LLM.
     * Cache key includes document URLs to prevent returning results for wrong document sets.
     */
    @Cacheable(
            value = "reranker-cache",
            key =
                    "#query + ':' + T(com.williamcallahan.javachat.service.RerankerService).computeDocsHash(#documents) + ':' + #returnK")
    public List<Document> rerank(String query, List<Document> documents, int returnK) {
        if (documents.size() <= 1) {
            return documents;
        }

        log.debug("Reranking {} documents", documents.size());

        Optional<String> llmOutputOptional = callLlmForReranking(query, documents);
        if (llmOutputOptional.isEmpty() || llmOutputOptional.get().isBlank()) {
            throw new RerankingFailureException("Reranking response was empty");
        }

        List<Document> reordered;
        try {
            reordered = parseRerankResponse(llmOutputOptional.get(), documents);
        } catch (JsonProcessingException jsonException) {
            throw new RerankingFailureException("Reranking response parse failed", jsonException);
        }
        if (reordered.isEmpty()) {
            throw new RerankingFailureException("Reranking response produced no valid ordering");
        }

        log.debug("Successfully reranked {} documents", reordered.size());
        return limitDocuments(reordered, returnK);
    }

    /**
     * Calls LLM service to get reranking order.
     *
     * @return reranking response, or empty if service unavailable or times out
     */
    private Optional<String> callLlmForReranking(String query, List<Document> documents) {
        if (openAIStreamingService == null || !openAIStreamingService.isAvailable()) {
            log.warn("OpenAIStreamingService unavailable; skipping LLM rerank");
            throw new RerankingFailureException("Reranking service unavailable");
        }

        String prompt = buildRerankPrompt(query, documents);

        try {
            return openAIStreamingService
                    .complete(prompt, 0.0)
                    .timeout(rerankerTimeout)
                    .doOnError(
                            timeoutOrApiError -> log.debug("Reranker LLM call timed out or failed", timeoutOrApiError))
                    .blockOptional();
        } catch (RuntimeException rerankFailure) {
            throw new RerankingFailureException(
                    "Reranking request failed within timeout " + rerankerTimeout, rerankFailure);
        }
    }

    /**
     * Build the prompt for the reranking LLM call.
     */
    private String buildRerankPrompt(String query, List<Document> documents) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document re-ranker for the Java learning assistant system.\n");
        prompt.append("Reorder the following documents by relevance to the query.\n");
        prompt.append("Consider Java-specific context, version relevance, and learning value.\n");
        prompt.append("Prefer official documentation over blogs or third-party sources.\n");
        prompt.append("Prefer stable release documentation over early-access or preview content.\n");
        prompt.append("Return JSON: {\"order\":[indices...]} with 0-based indices.\n\n");
        prompt.append("Query: ").append(query).append("\n\n");

        for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
            Document document = documents.get(docIndex);
            Map<String, ?> metadata = document.getMetadata();
            String title = extractMetadataString(metadata, "title");
            String url = extractMetadataString(metadata, "url");
            String text = document.getText();
            prompt.append("[")
                    .append(docIndex)
                    .append("] ")
                    .append(title)
                    .append(" | ")
                    .append(url)
                    .append("\n")
                    .append(trim(text == null ? "" : text, RERANK_PROMPT_TEXT_MAX_LENGTH))
                    .append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * Parse the LLM response to extract document ordering.
     */
    private List<Document> parseRerankResponse(String llmOutput, List<Document> documents)
            throws JsonProcessingException {
        List<Document> reordered = new ArrayList<>();
        RerankOrderResponse orderResponse = parseRerankOrderResponse(llmOutput);
        if (orderResponse == null || orderResponse.order() == null) {
            return reordered;
        }
        for (Integer documentIndex : orderResponse.order()) {
            if (documentIndex == null) {
                continue;
            }
            if (documentIndex >= 0 && documentIndex < documents.size()) {
                reordered.add(documents.get(documentIndex));
            }
        }
        return reordered;
    }

    /**
     * Extracts a JSON object from the LLM response, preferring fenced blocks when present.
     */
    private RerankOrderResponse parseRerankOrderResponse(String llmOutput) throws JsonProcessingException {
        String jsonObject = extractFirstJsonObject(llmOutput);
        if (jsonObject.isBlank()) {
            throw new RerankParsingException("Rerank response did not contain a JSON object");
        }
        try {
            return mapper.readerFor(RerankOrderResponse.class).readValue(jsonObject);
        } catch (IOException ioException) {
            throw new RerankParsingException("Failed to parse rerank JSON payload", ioException);
        }
    }

    private String extractFirstJsonObject(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return "";
        }
        String fencedCandidate = extractFirstFencedBlock(llmOutput);
        String candidate = fencedCandidate.isEmpty() ? llmOutput : fencedCandidate;
        return findFirstJsonObject(candidate);
    }

    private String extractFirstFencedBlock(String text) {
        int fenceStartIndex = text.indexOf(CODE_FENCE_MARKER);
        if (fenceStartIndex < 0) {
            return "";
        }
        int fenceLineBreakIndex = text.indexOf('\n', fenceStartIndex + CODE_FENCE_MARKER.length());
        if (fenceLineBreakIndex < 0) {
            return "";
        }
        int fenceEndIndex = text.indexOf(CODE_FENCE_MARKER, fenceLineBreakIndex + 1);
        if (fenceEndIndex < 0) {
            return "";
        }
        return text.substring(fenceLineBreakIndex + 1, fenceEndIndex).trim();
    }

    private String findFirstJsonObject(String text) {
        int firstBraceIndex = text.indexOf('{');
        if (firstBraceIndex < 0) {
            return "";
        }
        boolean inString = false;
        boolean escaped = false;
        int braceDepth = 0;
        for (int index = firstBraceIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (character == '\\') {
                    escaped = true;
                    continue;
                }
                if (character == '"') {
                    inString = false;
                }
                continue;
            }

            if (character == '"') {
                inString = true;
                continue;
            }

            if (character == '{') {
                braceDepth++;
            } else if (character == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    return text.substring(firstBraceIndex, index + 1).trim();
                }
            }
        }
        return text.substring(firstBraceIndex).trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankOrderResponse(
            @JsonProperty("order") List<Integer> order) {}

    /**
     * Limit document list to returnK elements.
     */
    private List<Document> limitDocuments(List<Document> documents, int returnK) {
        return documents.subList(0, Math.min(returnK, documents.size()));
    }

    private String trim(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private static String extractMetadataString(Map<String, ?> metadata, String key) {
        if (metadata == null) {
            return "";
        }
        Object metadataValue = metadata.get(key);
        if (metadataValue == null) {
            return "";
        }
        return String.valueOf(metadataValue);
    }

    /**
     * Compute a stable hash of documents for cache key.
     * Uses URLs as document identity since they are unique in the context of reranking.
     */
    public static String computeDocsHash(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "empty";
        }
        StringBuilder hashBuilder = new StringBuilder();
        for (Document document : documents) {
            Object url = document.getMetadata().get("url");
            String text = document.getText();
            hashBuilder.append(url != null ? url.toString() : (text != null ? text.hashCode() : 0));
            hashBuilder.append("|");
        }
        return Integer.toHexString(hashBuilder.toString().hashCode());
    }

    /**
     * Signals that a rerank response could not be parsed into the expected JSON structure.
     */
    private static final class RerankParsingException extends JsonProcessingException {
        private static final long serialVersionUID = 1L;

        private RerankParsingException(String message) {
            super(message);
        }

        private RerankParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
