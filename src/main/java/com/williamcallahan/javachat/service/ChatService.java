package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds chat prompts, enriches them with retrieval context, and delegates streaming to the LLM provider.
 */
@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    private final SystemPromptConfig systemPromptConfig;
    private final double temperature;

    /**
     * Creates the chat service with streaming, retrieval, and prompt configuration dependencies.
     *
     * @param openAIStreamingService LLM streaming service
     * @param retrievalService RAG retrieval service
     * @param systemPromptConfig system prompt configuration
     * @param appProperties application configuration for LLM settings
     */
    public ChatService(OpenAIStreamingService openAIStreamingService,
                       RetrievalService retrievalService,
                       SystemPromptConfig systemPromptConfig,
                       AppProperties appProperties) {
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
        this.systemPromptConfig = systemPromptConfig;
        this.temperature = appProperties.getLlm().getTemperature();
    }

    /**
     * Streaming via {@link OpenAIStreamingService}. This builds the prompt and streams with the SDK.
     */
    public Flux<String> streamAnswer(List<Message> history, String latestUserMessage) {
        int queryToken = Objects.hashCode(latestUserMessage);
        logger.debug("ChatService.streamAnswer called");
        
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
        
        logger.debug("ChatService configured with inline enrichment markers for queryToken={}", queryToken);

        appendContextDocs(systemContext, contextDocs);

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

        return openAIStreamingService.streamResponse(fullPrompt, temperature)
                .onErrorResume(streamingException -> {
                    logger.error("Streaming failed", streamingException);
                    return Flux.error(streamingException);
                });
    }

    /**
     * Appends context documents to a system prompt builder with normalized URLs.
     */
    private void appendContextDocs(StringBuilder systemContext, List<Document> contextDocs) {
        for (int docIndex = 0; docIndex < contextDocs.size(); docIndex++) {
            Document contextDoc = contextDocs.get(docIndex);
            Object urlMetadata = contextDoc.getMetadata().get("url");
            String rawUrl = urlMetadata != null ? urlMetadata.toString() : "";
            String normUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
            systemContext.append("\n[CTX ").append(docIndex + 1).append("] ").append(normUrl)
                .append("\n").append(contextDoc.getText());
        }
    }

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

        appendContextDocs(systemContext, contextDocs);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        String fullPrompt = buildPromptFromMessages(messages);

        return openAIStreamingService.streamResponse(fullPrompt, temperature)
                .onErrorResume(exception -> {
                    logger.error("Streaming failed", exception);
                    return Flux.error(exception);
                });
    }

    /**
     * Resolves citations for a query using the retrieval pipeline.
     */
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
     * Builds a full prompt with retrieval context for the default model.
     */
    public String buildPromptWithContext(List<Message> history, String latestUserMessage) {
        return buildPromptWithContext(history, latestUserMessage, null);
    }
    
    /**
     * Builds a full prompt with retrieval context and an optional model hint.
     */
    public String buildPromptWithContext(List<Message> history, String latestUserMessage, String modelHint) {
        return buildPromptWithContextOutcome(history, latestUserMessage, modelHint).prompt();
    }

    /**
     * Builds a prompt while returning retrieval notices for UI diagnostics.
     *
     * @param history existing chat history
     * @param latestUserMessage user query
     * @param modelHint optional model hint
     * @return prompt plus retrieval notices
     */
    public ChatPromptOutcome buildPromptWithContextOutcome(List<Message> history,
                                                          String latestUserMessage,
                                                          String modelHint) {
        // Use reduced RAG for token-constrained models (GPT-5.x family)
        RetrievalService.RetrievalOutcome retrievalOutcome;
        if (ModelConfiguration.isTokenConstrained(modelHint)) {
            retrievalOutcome = retrievalService.retrieveWithLimitOutcome(
                latestUserMessage,
                ModelConfiguration.RAG_LIMIT_CONSTRAINED,
                ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED
            );
            logger.debug("Using reduced RAG for {}: {} documents with max {} tokens each",
                modelHint,
                retrievalOutcome.documents().size(),
                ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED);
        } else {
            retrievalOutcome = retrievalService.retrieveOutcome(latestUserMessage);
        }

        List<Document> contextDocs = retrievalOutcome.documents();
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

        appendContextDocs(systemContext, contextDocs);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        return new ChatPromptOutcome(
                buildPromptFromMessages(messages),
                retrievalOutcome.notices(),
                retrievalOutcome.documents());
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

        appendContextDocs(systemContext, contextDocs);

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(systemContext.toString()));
        messages.addAll(history);
        messages.add(new UserMessage(latestUserMessage));

        return buildPromptFromMessages(messages);
    }

    /**
     * Determine the quality of search results and provide context to the AI.
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

    /**
     * Captures the prompt, retrieval notices, and source documents for UI diagnostics and citations.
     *
     * @param prompt generated prompt
     * @param notices retrieval notices to surface to clients
     * @param documents source documents used for RAG context (for inline citation emission)
     */
    public record ChatPromptOutcome(
            String prompt,
            List<RetrievalService.RetrievalNotice> notices,
            List<Document> documents) {
        public ChatPromptOutcome {
            if (prompt == null) {
                throw new IllegalArgumentException("Prompt cannot be null");
            }
            notices = notices == null ? List.of() : List.copyOf(notices);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
