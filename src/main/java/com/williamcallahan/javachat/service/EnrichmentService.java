package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Enrichment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class EnrichmentService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${APP_INFERENCE_PRIMARY_URL:http://localhost:8086}")
    private String inferencePrimaryBaseUrl;

    @Value("${APP_INFERENCE_SECONDARY_URL:http://localhost:8087}")
    private String inferenceSecondaryBaseUrl;

    @Value("${OPENAI_MODEL:gpt-4o-mini}")
    private String openaiModel;

    public EnrichmentService(ChatClient chatClient, ObjectMapper objectMapper, WebClient.Builder webClientBuilder) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
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

    private String tryOpenAiCompat(String prompt, String baseUrl) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
            Map<String, Object> body = Map.of(
                    "model", openaiModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.7
            );
            WebClient.RequestBodySpec req = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            if (openaiApiKey != null && !openaiApiKey.isBlank()) {
                req = req.header("Authorization", "Bearer " + openaiApiKey);
            }
            Map<?, ?> resp = req.bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                Object content = message != null ? message.get("content") : null;
                return content != null ? content.toString() : "{}";
            }
            return "{}";
        } catch (Exception e) {
            return null;
        }
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


