package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Enrichment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates enrichment metadata by prompting the LLM with contextual snippets.
 */
@Service
public class EnrichmentService {
    private static final Logger logger = LoggerFactory.getLogger(EnrichmentService.class);

    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper objectMapper;

    /**
     * Creates the enrichment service with JSON handling and LLM access.
     */
    public EnrichmentService(ObjectMapper objectMapper,
                             OpenAIStreamingService openAIStreamingService) {
        this.objectMapper = objectMapper.copy();
        this.openAIStreamingService = openAIStreamingService;
    }

    /**
     * Builds enrichment metadata for a user query and JDK version using context snippets.
     */
    @Cacheable(value = "enrichment-cache", key = "#userQuery + ':' + #jdkVersion")
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

        String json;
        try {
            if (openAIStreamingService != null && openAIStreamingService.isAvailable()) {
                json = openAIStreamingService.complete(prompt.toString(), 0.7).block();
            } else {
                logger.warn("OpenAIStreamingService unavailable; returning empty enrichment JSON");
                json = "{}";
            }
            if (json == null || json.isEmpty()) {
                logger.warn("Empty response from API, using fallback");
                json = "{}";
            }
        } catch (RuntimeException exception) {
            logger.warn("Enrichment service failed (exception type: {})", exception.getClass().getSimpleName());
            json = "{}"; // Return empty JSON for graceful degradation
        }

        String cleanedJson = cleanJson(json);
        try {
            Enrichment parsed = objectMapper
                    .reader()
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .forType(Enrichment.class)
                    .readValue(cleanedJson);

            if (parsed.getJdkVersion() == null || parsed.getJdkVersion().isBlank()) {
                parsed.setJdkVersion(jdkVersion);
            }
            if (parsed.getHints() == null) parsed.setHints(List.of());
            if (parsed.getReminders() == null) parsed.setReminders(List.of());
            if (parsed.getBackground() == null) parsed.setBackground(List.of());
            // Sanitize: trim and drop empty items across all lists
            parsed.setHints(trimFilter(parsed.getHints()));
            parsed.setReminders(trimFilter(parsed.getReminders()));
            parsed.setBackground(trimFilter(parsed.getBackground()));
            return parsed;
        } catch (JsonProcessingException exception) {
            Enrichment fallback = new Enrichment();
            fallback.setJdkVersion(jdkVersion);
            fallback.setHints(List.of());
            fallback.setReminders(List.of());
            fallback.setBackground(List.of());
            return fallback;
        }
    }

    private List<String> trimFilter(List<String> entries) {
        if (entries == null) return List.of();
        return entries.stream()
                .map(entryText -> entryText == null ? "" : entryText.trim())
                .filter(entryText -> !entryText.isEmpty())
                .toList();
    }




    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String trimmedJson = raw.trim();
        if (trimmedJson.startsWith("```")) {
            int firstBrace = trimmedJson.indexOf('{');
            int lastBrace = trimmedJson.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace >= firstBrace) {
                return trimmedJson.substring(firstBrace, lastBrace + 1);
            }
            int firstNewline = trimmedJson.indexOf('\n');
            if (firstNewline >= 0) {
                trimmedJson = trimmedJson.substring(firstNewline + 1);
            }
            int lastFence = trimmedJson.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmedJson = trimmedJson.substring(0, lastFence).trim();
            }
        }
        if (!trimmedJson.startsWith("{")) {
            int firstBrace = trimmedJson.indexOf('{');
            int lastBrace = trimmedJson.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace >= firstBrace) {
                return trimmedJson.substring(firstBrace, lastBrace + 1);
            }
        }
        return trimmedJson;
    }
}
