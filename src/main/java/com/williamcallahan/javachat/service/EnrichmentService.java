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

@Service
public class EnrichmentService {
    private static final Logger logger = LoggerFactory.getLogger(EnrichmentService.class);

    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper objectMapper;

    public EnrichmentService(ObjectMapper objectMapper,
                             OpenAIStreamingService openAIStreamingService) {
        this.objectMapper = objectMapper;
        this.openAIStreamingService = openAIStreamingService;
    }

    @Cacheable(value = "enrichment-cache", key = "#userQuery + ':' + #jdkVersion")
    public Enrichment enrich(String userQuery, String jdkVersion, List<String> contextSnippets) {
        logger.debug("EnrichmentService.enrich called for query: {}", userQuery);
        StringBuilder prompt = new StringBuilder();
        // Add base system context for consistency
        prompt.append("You are operating as part of the Java learning assistant system.\n");
        prompt.append("Extract enrichment metadata to enhance learning from the provided context.\n\n");
        prompt.append("User query: ").append(userQuery).append("\n\n");
        prompt.append("Context snippets from Java documentation:\n");
        for (int i = 0; i < contextSnippets.size(); i++) {
            prompt.append("- ").append(contextSnippets.get(i)).append("\n");
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
        } catch (Exception ex) {
            logger.warn("Enrichment service failed for query '{}': {}", userQuery, ex.getMessage());
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
        } catch (JsonProcessingException ex) {
            Enrichment fallback = new Enrichment();
            fallback.setJdkVersion(jdkVersion);
            fallback.setHints(List.of());
            fallback.setReminders(List.of());
            fallback.setBackground(List.of());
            return fallback;
        }
    }

    private List<String> trimFilter(List<String> in) {
        if (in == null) return List.of();
        return in.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .toList();
    }




    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```") ) {
            int first = s.indexOf('{');
            int last = s.lastIndexOf('}');
            if (first >= 0 && last >= first) {
                return s.substring(first, last + 1);
            }
            s = s.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("```\\s*", "");
        }
        if (!s.startsWith("{") ) {
            int first = s.indexOf('{');
            int last = s.lastIndexOf('}');
            if (first >= 0 && last >= first) {
                return s.substring(first, last + 1);
            }
        }
        return s;
    }
}

