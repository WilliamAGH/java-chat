package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.model.Enrichment;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Generates enrichment metadata by prompting the LLM with contextual snippets.
 */
@Service
public class EnrichmentService {
    private static final Logger logger = LoggerFactory.getLogger(EnrichmentService.class);
    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper objectMapper;
    private final double enrichmentTemperature;
    private final int enrichmentOutputTokenBudget;

    /**
     * Creates the enrichment service with JSON handling and LLM access.
     */
    public EnrichmentService(
            ObjectMapper objectMapper, OpenAIStreamingService openAIStreamingService, AppProperties appProperties) {
        this.objectMapper = objectMapper.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        this.objectMapper
                .coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        this.openAIStreamingService = openAIStreamingService;
        this.enrichmentTemperature = appProperties.getLlm().getTemperature();
        this.enrichmentOutputTokenBudget = appProperties.getLlm().getEnrichmentOutputTokenBudget();
    }

    /**
     * Builds enrichment metadata for a user query and JDK version using context snippets.
     */
    @Cacheable("enrichment-cache")
    public Enrichment enrich(String userQuery, String jdkVersion, List<String> contextSnippets) {
        logger.debug("EnrichmentService.enrich called");
        StringBuilder prompt = new StringBuilder();
        // Add base system context for consistency
        prompt.append("You are operating as part of the Java learning assistant system.\n");
        prompt.append("Extract enrichment metadata to enhance learning from the provided context.\n\n");
        prompt.append("User query: ").append(userQuery).append("\n\n");
        prompt.append("Context snippets from Java documentation:\n");
        for (int snippetIndex = 0; snippetIndex < contextSnippets.size(); snippetIndex++) {
            prompt.append("- ").append(contextSnippets.get(snippetIndex)).append("\n");
        }
        prompt.append("\nRespond as JSON with these fields:\n");
        prompt.append("- hints[]: Practical tips and best practices (2-3 items max)\n");
        prompt.append("- reminders[]: Critical things to remember (2-3 items max)\n");
        prompt.append("- background[]: Conceptual explanations for deeper understanding (2-3 items max)\n");
        prompt.append("- packageName: The main Java package being discussed\n");
        prompt.append("- jdkVersion: '").append(jdkVersion).append("' unless docs indicate otherwise\n");
        prompt.append("- resource: Primary documentation source\n");
        prompt.append("- resourceVersion: Version of the resource\n");
        prompt.append("\nMake each item concise but informative. Focus on learning value.\n");
        prompt.append("Use your knowledge of Java best practices and common pitfalls.\n");

        if (openAIStreamingService == null || !openAIStreamingService.isAvailable()) {
            throw new IllegalStateException("OpenAIStreamingService unavailable for enrichment");
        }

        String enrichmentJson = openAIStreamingService
                .completeJsonObject(prompt.toString(), enrichmentTemperature, enrichmentOutputTokenBudget)
                .block();
        if (enrichmentJson == null || enrichmentJson.isBlank()) {
            throw new IllegalStateException("LLM returned empty enrichment response");
        }

        try {
            Enrichment enrichment = objectMapper
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .forType(Enrichment.class)
                    .readValue(enrichmentJson);

            if (enrichment.getJdkVersion() == null || enrichment.getJdkVersion().isBlank()) {
                enrichment.setJdkVersion(jdkVersion);
            }
            if (enrichment.getHints() == null) enrichment.setHints(List.of());
            if (enrichment.getReminders() == null) enrichment.setReminders(List.of());
            if (enrichment.getBackground() == null) enrichment.setBackground(List.of());
            return enrichment.sanitized();
        } catch (JsonProcessingException jsonParseException) {
            throw new IllegalStateException(
                    "LLM enrichment response was not valid JSON: " + jsonParseException.getMessage(),
                    jsonParseException);
        }
    }
}
