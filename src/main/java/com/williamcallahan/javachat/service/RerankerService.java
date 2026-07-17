package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.williamcallahan.javachat.config.AppProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /** Maximum character length of document text included in the rerank prompt. */
    private static final int RERANK_PROMPT_TEXT_MAX_LENGTH = 500;

    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper mapper;
    private final Duration rerankerTimeout;
    private final double rerankerTemperature;
    private final int rerankerOutputTokenBudget;

    /**
     * Creates a reranker backed by the streaming LLM client.
     *
     * @param openAIStreamingService streaming LLM client
     * @param objectMapper Jackson object mapper
     * @param appProperties application configuration containing reranker request settings
     */
    public RerankerService(
            OpenAIStreamingService openAIStreamingService, ObjectMapper objectMapper, AppProperties appProperties) {
        this.openAIStreamingService = openAIStreamingService;
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        this.mapper
                .coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        AppProperties configuredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.Llm llmConfiguration = configuredAppProperties.getLlm();
        this.rerankerTimeout = configuredAppProperties.getRag().getRerankerTimeout();
        this.rerankerTemperature = llmConfiguration.getRerankerTemperature();
        this.rerankerOutputTokenBudget = llmConfiguration.getRerankerOutputTokenBudget();
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
                    .completeJsonObject(prompt, rerankerTemperature, rerankerOutputTokenBudget, rerankerTimeout)
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
        prompt.append("There are exactly ")
                .append(documents.size())
                .append(" documents. Valid indices are 0 through ")
                .append(documents.size() - 1)
                .append(".\n");
        prompt.append("Include each valid index exactly once and do not return any other values.\n");
        prompt.append("Return only JSON: {\"order\":[indices...]} with 0-based indices.\n");
        prompt.append("Do not include markdown, prose, or explanations.\n\n");
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

    /** Parses an untrusted LLM ordering only when it is a complete source-index permutation. */
    private List<Document> parseRerankResponse(String llmOutput, List<Document> documents)
            throws JsonProcessingException {
        List<Document> reordered = new ArrayList<>();
        Set<Integer> includedDocumentIndices = new HashSet<>();
        RerankOrderResponse orderResponse = parseRerankOrderResponse(llmOutput);
        if (orderResponse == null
                || orderResponse.order() == null
                || orderResponse.order().size() != documents.size()) {
            throw new RerankParsingException("Rerank order must contain every source index exactly once");
        }
        for (Integer documentIndex : orderResponse.order()) {
            if (documentIndex == null
                    || documentIndex < 0
                    || documentIndex >= documents.size()
                    || !includedDocumentIndices.add(documentIndex)) {
                throw new RerankParsingException("Rerank order must be a permutation of all source indices");
            }
            reordered.add(documents.get(documentIndex));
        }
        return reordered;
    }

    /** Parses the complete structured response without prose or fenced compatibility paths. */
    private RerankOrderResponse parseRerankOrderResponse(String llmOutput) throws JsonProcessingException {
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new RerankParsingException("Rerank response was empty");
        }
        try {
            return mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .forType(RerankOrderResponse.class)
                    .readValue(llmOutput);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RerankParsingException("Failed to parse rerank JSON payload", jsonProcessingException);
        }
    }

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
