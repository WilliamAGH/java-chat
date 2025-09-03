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
        prompt.append("Extract enrichment for a Java learning assistant to help users understand concepts better.\n");
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

        String json;
        try {
            json = chatClient.prompt().user(prompt.toString()).call().content();
        } catch (Exception ex) {
            if (isUnauthorized(ex)) {
                json = openaiFallback(prompt.toString());
            } else {
                throw ex;
            }
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

    private boolean isUnauthorized(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("401") && msg.toLowerCase().contains("unauthorized")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String openaiFallback(String prompt) {
        // Return empty JSON if no fallback available
        // Local inference servers not configured/running
        return "{}";
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

