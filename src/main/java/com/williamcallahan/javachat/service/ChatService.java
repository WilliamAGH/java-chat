package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    
    private final ChatClient chatClient;
    private final RetrievalService retrievalService;
    private final WebClient webClient;
    
    @Autowired
    private MarkdownService markdownService;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${APP_INFERENCE_PRIMARY_URL:https://api.openai.com}")
    private String inferencePrimaryBaseUrl;

    @Value("${APP_INFERENCE_SECONDARY_URL:https://models.github.ai/inference}")
    private String inferenceSecondaryBaseUrl;

    @Value("${OPENAI_MODEL:gpt-4o-mini}")
    private String openaiModel;

    public ChatService(ChatClient chatClient, RetrievalService retrievalService, WebClient.Builder webClientBuilder) {
        this.chatClient = chatClient;
        this.retrievalService = retrievalService;
        this.webClient = webClientBuilder.build();
    }

    public Flux<String> streamAnswer(List<Message> history, String latestUserMessage) {
        logger.debug("ChatService.streamAnswer called for query: {}", latestUserMessage);
        
        // Retrieve context and provide user feedback about search quality
        List<Document> contextDocs = retrievalService.retrieve(latestUserMessage);
        String searchQualityNote = determineSearchQuality(contextDocs);
        
        StringBuilder systemContext = new StringBuilder(
            "You are a Java learning assistant with knowledge of Java 24, Java 25, and Java 25 EA features. Use the provided context to answer questions.\n" +
            "CRITICAL: Embed learning insights directly in your response using these markers. EACH marker MUST be on its own line.\n" +
            "- {{hint:Text here}} for helpful tips and best practices\n" +
            "- {{reminder:Text here}} for important things to remember\n" +
            "- {{background:Text here}} for conceptual explanations\n" +
            "- {{example:code here}} for inline code examples\n" +
            "- {{warning:Text here}} for common pitfalls to avoid\n" +
            "- [n] for citations with the source URL\n\n" +
            "Integrate these naturally into your explanation. Don't group them at the end.\n"
        );
        
        // Add search quality context for the AI
        if (!searchQualityNote.isEmpty()) {
            systemContext.append("\nSEARCH CONTEXT: ").append(searchQualityNote).append("\n");
        }
        
        logger.debug("ChatService configured with inline enrichment markers for query: {}", latestUserMessage);

        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(d.getMetadata().get("url")).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));
        Message[] msgs = messages.toArray(new Message[0]);

        String fallbackPrompt = systemContext + "\n\n" + latestUserMessage;

        return chatClient
                .prompt().messages(msgs).stream().content()
                .onErrorResume(ex -> isFallbackRequired(ex) ? openAiOnce(fallbackPrompt) : Flux.error(ex));
    }

    /**
     * Stream answer reusing existing pipeline but with preselected context documents
     * and optional guidance to prepend to the system context.
     */
    public Flux<String> streamAnswerWithContext(List<Message> history,
                                                String latestUserMessage,
                                                List<Document> contextDocs,
                                                String guidance) {
        if (contextDocs == null) contextDocs = List.of();
        StringBuilder systemContext = new StringBuilder();
        if (guidance != null && !guidance.isBlank()) {
            systemContext.append(guidance).append("\n\n");
        }
        systemContext.append(
            "Use the provided context to answer questions.\n" +
            "CRITICAL: Embed learning insights directly in your response using these markers. EACH marker MUST be on its own line.\n" +
            "- {{hint:Text here}} for helpful tips and best practices\n" +
            "- {{reminder:Text here}} for important things to remember\n" +
            "- {{background:Text here}} for conceptual explanations\n" +
            "- {{example:code here}} for inline code examples\n" +
            "- {{warning:Text here}} for common pitfalls to avoid\n" +
            "- [n] for citations with the source URL\n\n" +
            "Integrate these naturally into your explanation. Don't group them at the end.\n"
        );

        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(d.getMetadata().get("url")).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));
        Message[] msgs = messages.toArray(new Message[0]);

        String fallbackPrompt = systemContext + "\n\n" + latestUserMessage;

        return chatClient
                .prompt().messages(msgs).stream().content()
                .onErrorResume(ex -> isFallbackRequired(ex) ? openAiOnce(fallbackPrompt) : Flux.error(ex));
    }

    public List<Citation> citationsFor(String userQuery) {
        List<Document> docs = retrievalService.retrieve(userQuery);
        return retrievalService.toCitations(docs);
    }

    private boolean isFallbackRequired(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("429"))) {
                if (msg.contains("429")) {
                    logger.warn("Rate limit hit with primary API, attempting fallback");
                } else {
                    logger.warn("Authentication failed with primary API, attempting fallback");
                }
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private Flux<String> openAiOnce(String prompt) {
        // For fallback, prioritize OpenAI API (different rate limits than GitHub Models)
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            logger.debug("Using OpenAI API for fallback");
            return tryOpenAiCompat(prompt, "https://api.openai.com", openaiApiKey);
        } else if (githubToken != null && !githubToken.isBlank()) {
            logger.debug("Using GitHub Models for fallback (same rate limits apply)");
            logger.warn("Fallback to GitHub Models may still hit rate limits - consider setting OPENAI_API_KEY");
            return tryOpenAiCompat(prompt, "https://models.github.ai/inference", githubToken);
        }
        // If no API keys available, return error
        logger.error("No API key configured for chat service");
        return Flux.error(new RuntimeException("No API key configured. Please set either OPENAI_API_KEY or GITHUB_TOKEN"));
    }
    

    private Flux<String> tryOpenAiCompat(String prompt, String baseUrl, String apiKey) {
        String url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
        logger.debug("Attempting API call to: {}", baseUrl);
        
        Map<String, Object> body = Map.of(
                "model", openaiModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7
        );
        WebClient.RequestBodySpec req = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            req = req.header("Authorization", "Bearer " + apiKey);
        }
        return req
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                            Object content = message != null ? message.get("content") : null;
                            return content != null ? content.toString() : "";
                        }
                        return "";
                    } catch (Exception e) {
                        return "";
                    }
                })
                .flatMapMany(text -> Flux.just(text));
    }
    
    /**
     * Process response text with markdown rendering.
     * This can be used to pre-render markdown on the server side.
     * 
     * @param text The raw text response from AI
     * @return HTML-rendered markdown
     */
    public String processResponseWithMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        try {
            // Render markdown to HTML
            String html = markdownService.render(text);
            logger.debug("Processed response with markdown rendering");
            return html;
        } catch (Exception e) {
            logger.error("Error processing response with markdown", e);
            // Fallback to plain text with basic escaping
            return text.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\n", "<br />\n");
        }
    }
    
    /**
     * Stream answers with optional markdown processing.
     * Each chunk can be processed through markdown if needed.
     */
    public Flux<String> streamAnswerWithMarkdown(List<Message> history, String latestUserMessage, boolean renderMarkdown) {
        Flux<String> baseStream = streamAnswer(history, latestUserMessage);
        
        if (!renderMarkdown) {
            return baseStream;
        }
        
        // For streaming with markdown, we need to buffer complete sentences/paragraphs
        // This is complex for streaming, so typically markdown is applied client-side
        // or after the full response is received
        return baseStream;
    }
    
    /**
     * Determine the quality of search results and provide context to the AI
     */
    private String determineSearchQuality(List<Document> docs) {
        if (docs.isEmpty()) {
            return "No relevant documents found. Using general knowledge only.";
        }
        
        // Check if documents seem to be from keyword search (less semantic relevance)
        boolean likelyKeywordSearch = docs.stream()
            .anyMatch(doc -> {
                String url = String.valueOf(doc.getMetadata().getOrDefault("url", ""));
                return url.contains("local-search") || url.contains("keyword");
            });
        
        if (likelyKeywordSearch) {
            return String.format("Found %d documents via keyword search (embedding service unavailable). Results may be less semantically relevant.", docs.size());
        }
        
        // Check document relevance quality
        long highQualityDocs = docs.stream()
            .filter(doc -> {
                String content = doc.getText(); // Use getText() instead of getContent()
                return content != null && content.length() > 100; // Basic quality check
            })
            .count();
        
        if (highQualityDocs == docs.size()) {
            return String.format("Found %d high-quality relevant documents via semantic search.", docs.size());
        } else {
            return String.format("Found %d documents (%d high-quality) via search. Some results may be less relevant.", 
                docs.size(), highQualityDocs);
        }
    }
}
