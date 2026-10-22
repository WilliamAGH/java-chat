package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.CompletionPrompt;
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
        String instructions = """
                You are operating as part of the Java learning assistant system.
                Extract enrichment metadata to enhance learning from the provided context.
                Respond as JSON with these fields:
                - hints[]: Practical tips and best practices (2-3 items max)
                - reminders[]: Critical things to remember (2-3 items max)
                - background[]: Conceptual explanations for deeper understanding (2-3 items max)
                - packageName: The main Java package being discussed
                - jdkVersion: The requested JDK version unless docs indicate otherwise
                - resource: Primary documentation source
                - resourceVersion: Version of the resource
                Make each item concise but informative. Focus on learning value.
                Use your knowledge of Java best practices and common pitfalls.
                """;
        StringBuilder promptInput = new StringBuilder("Requested JDK version: ")
                .append(jdkVersion)
                .append("\nUser query: ")
                .append(userQuery)
                .append("\n\nContext snippets from Java documentation:\n");
        for (String contextSnippet : contextSnippets) {
            promptInput.append("- ").append(contextSnippet).append("\n");
        }
        CompletionPrompt prompt = new CompletionPrompt(instructions, promptInput.toString());

        if (openAIStreamingService == null || !openAIStreamingService.isAvailable()) {
            throw new IllegalStateException("OpenAIStreamingService unavailable for enrichment");
        }

        String enrichmentJson = openAIStreamingService
                .completeJsonObject(
                        LlmGatewayTier.Purpose.MARKDOWN_ENRICHMENT,
                        prompt,
                        enrichmentTemperature,
                        enrichmentOutputTokenBudget)
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
