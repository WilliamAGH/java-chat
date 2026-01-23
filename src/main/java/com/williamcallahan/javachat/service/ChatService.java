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
    
    // OpenAI streaming preferred; ChatService builds prompts and can stream via SDK for internal uses
    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    private final SystemPromptConfig systemPromptConfig;
    
    @Autowired
    private MarkdownService markdownService;

    public ChatService(OpenAIStreamingService openAIStreamingService,
                       RetrievalService retrievalService,
                       SystemPromptConfig systemPromptConfig) {
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
        this.systemPromptConfig = systemPromptConfig;
    }

    /**
     * Streaming via {@link OpenAIStreamingService}. This builds the prompt and streams with the SDK.
     */
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
            String rawUrl = String.valueOf(d.getMetadata().get("url"));
            String normUrl = normalizeUrlForPrompt(rawUrl);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(normUrl).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));
        
        String fullPrompt = buildPromptFromMessages(messages);

        // DIAGNOSTIC: Log prompt size only (no content) at DEBUG to avoid leaking sensitive data
        logger.debug("[DIAG] LLM prompt length={}", fullPrompt.length());

        if (!openAIStreamingService.isAvailable()) {
            logger.error("OpenAI streaming service is not available - check API credentials");
            return Flux.error(new IllegalStateException("Chat service unavailable - no API credentials configured"));
        }

        return openAIStreamingService.streamResponse(fullPrompt, 0.7)
                .onErrorResume(ex -> {
                    logger.error("Streaming failed", ex);
                    return Flux.error(ex);
                });
    }

    /**
     * Normalize URLs placed into the LLM prompt so the model never sees local file:/// paths
     * or malformed mirrors. This mirrors RetrievalService's normalization without exposing it.
     */
    private String normalizeUrlForPrompt(String url) {
        if (url == null || url.isBlank()) return url;
        String u = url.trim();
        // Already HTTP(S): canonicalize and fix common spring paths
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return canonicalizeHttpDocUrl(u);
        }
        // Map book PDFs to public server path
        String publicPdf = com.williamcallahan.javachat.config.DocsSourceRegistry.mapBookLocalToPublic(u.startsWith("file://") ? u.substring("file://".length()) : u);
        if (publicPdf != null) return publicPdf;
        // Only handle file:// beyond this point
        if (!u.startsWith("file://")) return u;
        String p = u.substring("file://".length());
        String embedded = com.williamcallahan.javachat.config.DocsSourceRegistry.reconstructFromEmbeddedHost(p);
        if (embedded != null) return canonicalizeHttpDocUrl(embedded);
        String mapped = com.williamcallahan.javachat.config.DocsSourceRegistry.mapLocalPrefixToRemote(p);
        return mapped != null ? canonicalizeHttpDocUrl(mapped) : u; // final fallback: keep original
    }

    private String canonicalizeHttpDocUrl(String url) {
        String out = url;
        out = out.replace("/docs/api/api/", "/docs/api/");
        out = out.replace("/api/api/", "/api/");
        if (out.contains("https://docs.spring.io/")) {
            out = out.replace("/spring-boot/docs/current/api/java/", "/spring-boot/docs/current/api/");
            out = out.replace("/spring-framework/docs/current/javadoc-api/java/", "/spring-framework/docs/current/javadoc-api/");
        }
        int protoIdx = out.indexOf("://");
        String prefix = protoIdx >= 0 ? out.substring(0, protoIdx + 3) : "";
        String rest = protoIdx >= 0 ? out.substring(protoIdx + 3) : out;
        rest = rest.replaceAll("/+", "/");
        return prefix + rest;
    }

    /**
     * Stream answer reusing existing pipeline but with preselected context documents
     * and optional guidance to prepend to the system context.
     */
    /**
     * Legacy streaming with preselected context. Prefer building a prompt with
     * {@link #buildPromptWithContextAndGuidance(List, String, List, String)} and
     * using {@link OpenAIStreamingService} to stream.
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
            String rawUrl = String.valueOf(d.getMetadata().get("url"));
            String safeUrl = normalizeUrlForPrompt(rawUrl);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(safeUrl).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        String fullPrompt = buildPromptFromMessages(messages);

        return openAIStreamingService.streamResponse(fullPrompt, 0.7)
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
     * Build a complete prompt with context for OpenAI streaming service.
     * This reuses the existing prompt building logic from streamAnswer.
     */
    public String buildPromptWithContext(List<Message> history, String latestUserMessage) {
        return buildPromptWithContext(history, latestUserMessage, null);
    }
    
    public String buildPromptWithContext(List<Message> history, String latestUserMessage, String modelHint) {
        // For GPT-5, use fewer RAG documents due to 8K token input limit
        List<Document> contextDocs;
        if ("gpt-5".equals(modelHint) || "gpt-5-chat".equals(modelHint)) {
            // Limit RAG for GPT-5: use fewer, shorter documents
            contextDocs = retrievalService.retrieveWithLimit(latestUserMessage, 3, 600); // 3 docs, 600 tokens each = ~1800 tokens
            logger.debug("Using reduced RAG for GPT-5: {} documents with max 600 tokens each", contextDocs.size());
        } else {
            contextDocs = retrievalService.retrieve(latestUserMessage);
        }
        
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

        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            String rawUrl = String.valueOf(d.getMetadata().get("url"));
            String safeUrl = normalizeUrlForPrompt(rawUrl);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(safeUrl).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        return buildPromptFromMessages(messages);
    }

    /**
     * Build a complete prompt with context and guidance for OpenAI streaming service.
     * Used by GuidedLearningService for lesson-specific prompts.
     */
    public String buildPromptWithContextAndGuidance(List<Message> history, String latestUserMessage,
                                                   List<Document> contextDocs, String guidance) {
        // Build system prompt with guidance
        String basePrompt = systemPromptConfig.getCoreSystemPrompt();
        String completePrompt = guidance != null && !guidance.isBlank()
            ? systemPromptConfig.buildFullPrompt(basePrompt, guidance)
            : basePrompt;

        StringBuilder systemContext = new StringBuilder(completePrompt);

        for (int i = 0; i < contextDocs.size(); i++) {
            Document d = contextDocs.get(i);
            String rawUrl = String.valueOf(d.getMetadata().get("url"));
            String safeUrl = normalizeUrlForPrompt(rawUrl);
            systemContext.append("\n[CTX ").append(i + 1).append("] ").append(safeUrl).append("\n").append(d.getText());
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));
        
        return buildPromptFromMessages(messages);
    }
    
    /**
     * Process response text with markdown rendering.
     * This can be used to pre-render markdown on the server side.
     * 
     * @param text The raw text response from AI
     * @return HTML-rendered markdown
     */
    /**
     * Legacy markdown rendering path. Prefer {@link UnifiedMarkdownService}
     * integration where possible and avoid rendering on the hot path.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public String processResponseWithMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        try {
            // Use new AST-based processing for better compliance
            var processed = markdownService.processStructured(text);
            logger.debug("Processed response with AST-based markdown rendering");
            return processed.html();
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
    /**
     * Legacy streaming with optional markdown render. Use OpenAIStreamingService instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
