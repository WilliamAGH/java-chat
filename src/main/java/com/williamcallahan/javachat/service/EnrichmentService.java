package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Enrichment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EnrichmentService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public EnrichmentService(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    public Enrichment enrich(String userQuery, String jdkVersion, List<String> contextSnippets) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Extract enrichment for a Java learning assistant. Return compact bullet points.\n");
        prompt.append("User query: ").append(userQuery).append("\n\n");
        prompt.append("Context snippets from docs (may be truncated):\n");
        for (int i = 0; i < contextSnippets.size(); i++) {
            prompt.append("- ").append(contextSnippets.get(i)).append("\n");
        }
        prompt.append("\nRespond as JSON with fields: packageName, jdkVersion, resource, resourceVersion, hints[], reminders[], background[]. ");
        prompt.append("Use JDK version '").append(jdkVersion).append("' unless contradicted by context.\n");

        String json = chatClient.prompt().user(prompt.toString()).call().content();

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

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('{');
            int last = s.lastIndexOf('}');
            if (first >= 0 && last >= first) {
                return s.substring(first, last + 1);
            }
            s = s.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("```\\s*", "");
        }
        if (!s.startsWith("{")) {
            int first = s.indexOf('{');
            int last = s.lastIndexOf('}');
            if (first >= 0 && last >= first) {
                return s.substring(first, last + 1);
            }
        }
        return s;
    }
}


