package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    
    private final ResilientApiClient apiClient;
    private final RetrievalService retrievalService;
    private final SystemPromptConfig systemPromptConfig;
    
    @Autowired
    private MarkdownService markdownService;

    public ChatService(ResilientApiClient apiClient, RetrievalService retrievalService, 
                      SystemPromptConfig systemPromptConfig) {
        this.apiClient = apiClient;
        this.retrievalService = retrievalService;
        this.systemPromptConfig = systemPromptConfig;
    }

    public Flux<String> streamAnswer(List<Message> history, String latestUserMessage) {
        logger.debug("ChatService.streamAnswer called for query: {}", latestUserMessage);
        
        // Retrieve context and provide user feedback about search quality
        List<Document> contextDocs = retrievalService.retrieve(latestUserMessage);
        String searchQualityNote = determineSearchQuality(contextDocs);
        
        // Build system prompt using centralized configuration
        StringBuilder systemContext = new StringBuilder(systemPromptConfig.getCoreSystemPrompt());
        
        // Add search quality context if needed
        if (!searchQualityNote.isEmpty()) {
            systemContext.append("\n\nSEARCH CONTEXT: ").append(searchQualityNote);
            
            // Add low quality search guidance if applicable
            if (searchQualityNote.contains("less relevant") || searchQualityNote.contains("keyword search")) {
                systemContext.append("\n").append(systemPromptConfig.getLowQualitySearchPrompt());
            }
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
        
        String fullPrompt = buildPromptFromMessages(messages);

        return apiClient.streamLLM(fullPrompt, 0.7)
                .onErrorResume(ex -> {
                    logger.error("Streaming failed", ex);
                    return Flux.error(ex);
                });
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
        
        // Build system prompt with optional guidance
        String basePrompt = systemPromptConfig.getCoreSystemPrompt();
        String completePrompt = guidance != null && !guidance.isBlank() 
            ? systemPromptConfig.buildFullPrompt(basePrompt, guidance)
            : basePrompt;
        
        StringBuilder systemContext = new StringBuilder(completePrompt);

        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(d.getMetadata().get("url")).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));
        
        String fullPrompt = buildPromptFromMessages(messages);

        return apiClient.streamLLM(fullPrompt, 0.7)
                .onErrorResume(ex -> {
                    logger.error("Streaming failed", ex);
                    return Flux.error(ex);
                });
    }

    public List<Citation> citationsFor(String userQuery) {
        List<Document> docs = retrievalService.retrieve(userQuery);
        return retrievalService.toCitations(docs);
    }

    private String buildPromptFromMessages(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) msg;
                prompt.append(userMsg.getText()).append("\n\n");
            }
        }
        return prompt.toString().trim();
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
        return streamAnswer(history, latestUserMessage);
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
