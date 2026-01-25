package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.SearchQualityLevel;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.CurrentQuerySegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.prompt.SystemSegment;
import com.williamcallahan.javachat.support.DocumentContentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

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
     * Streams a chat response with retrieval-augmented context using structured prompts.
     *
     * <p>Builds a structured prompt for intelligent segment-based truncation, then
     * delegates streaming to the LLM provider.</p>
     *
     * @param history conversation history
     * @param latestUserMessage user query
     * @return streaming response chunks
     */
    public Flux<String> streamAnswer(List<Message> history, String latestUserMessage) {
        logger.debug("ChatService.streamAnswer called");

        StructuredPromptOutcome outcome = buildStructuredPromptWithContextOutcome(
                history, latestUserMessage, null);

        if (!openAIStreamingService.isAvailable()) {
            logger.error("OpenAI streaming service is not available - check API credentials");
            return Flux.error(new IllegalStateException("Chat service unavailable - no API credentials configured"));
        }

        return openAIStreamingService.streamResponse(outcome.structuredPrompt(), temperature)
                .onErrorResume(streamingException -> {
                    logger.error("Streaming failed", streamingException);
                    return Flux.error(streamingException);
                });
    }

    /**
     * Streams a chat response with preselected context and custom guidance using structured prompts.
     *
     * <p>Uses structure-aware truncation to preserve semantic boundaries when the prompt
     * exceeds model token limits.</p>
     *
     * @param history conversation history
     * @param latestUserMessage user query
     * @param contextDocs pre-selected context documents
     * @param guidance custom system guidance to append
     * @return streaming response chunks
     */
    public Flux<String> streamAnswerWithContext(List<Message> history,
                                                String latestUserMessage,
                                                List<Document> contextDocs,
                                                String guidance) {
        if (contextDocs == null) contextDocs = List.of();

        StructuredPrompt structuredPrompt = buildStructuredPromptWithContextAndGuidance(
                history, latestUserMessage, contextDocs, guidance);

        return openAIStreamingService.streamResponse(structuredPrompt, temperature)
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

    /**
     * Builds a structured prompt with pre-selected context and custom guidance.
     *
     * <p>Used by guided learning flows where context documents are pre-filtered
     * (e.g., to a specific book) and custom guidance is provided.</p>
     *
     * @param history conversation history
     * @param latestUserMessage user query
     * @param contextDocs pre-selected context documents
     * @param guidance custom system guidance to append
     * @return structured prompt for intelligent truncation
     */
    public StructuredPrompt buildStructuredPromptWithContextAndGuidance(
            List<Message> history,
            String latestUserMessage,
            List<Document> contextDocs,
            String guidance) {

        // Build system prompt with guidance
        String basePrompt = systemPromptConfig.getCoreSystemPrompt();
        String completePrompt = guidance != null && !guidance.isBlank()
                ? systemPromptConfig.buildFullPrompt(basePrompt, guidance)
                : basePrompt;

        SystemSegment systemSegment = new SystemSegment(
                completePrompt,
                estimateTokens(completePrompt)
        );

        List<ContextDocumentSegment> contextSegments = buildContextSegments(
                contextDocs != null ? contextDocs : List.of()
        );

        List<ConversationTurnSegment> conversationSegments = buildConversationSegments(history);

        CurrentQuerySegment querySegment = new CurrentQuerySegment(
                latestUserMessage,
                estimateTokens(latestUserMessage)
        );

        return new StructuredPrompt(
                systemSegment,
                contextSegments,
                conversationSegments,
                querySegment
        );
    }

    /**
     * Builds a structured prompt with retrieval context for intelligent truncation.
     *
     * <p>Returns a StructuredPrompt that can be truncated segment-by-segment
     * rather than character-by-character, preserving semantic boundaries.</p>
     *
     * @param history existing chat history
     * @param latestUserMessage user query
     * @param modelHint optional model hint for RAG optimization
     * @return structured prompt outcome with segments and retrieval metadata
     */
    public StructuredPromptOutcome buildStructuredPromptWithContextOutcome(
            List<Message> history,
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
            logger.debug("Using reduced RAG: {} documents with max {} tokens each",
                retrievalOutcome.documents().size(),
                ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED);
        } else {
            retrievalOutcome = retrievalService.retrieveOutcome(latestUserMessage);
        }

        List<Document> contextDocs = retrievalOutcome.documents();
        String searchQualityNote = determineSearchQuality(contextDocs);

        // Build system prompt with search quality context
        StringBuilder systemPromptBuilder = new StringBuilder(systemPromptConfig.getCoreSystemPrompt());
        if (!searchQualityNote.isEmpty()) {
            systemPromptBuilder.append("\n\nSEARCH CONTEXT: ").append(searchQualityNote);
            if (searchQualityNote.contains("less relevant") || searchQualityNote.contains("keyword search")) {
                systemPromptBuilder.append("\n").append(systemPromptConfig.getLowQualitySearchPrompt());
            }
        }
        String systemPromptText = systemPromptBuilder.toString();

        // Build structured segments
        SystemSegment systemSegment = new SystemSegment(
                systemPromptText,
                estimateTokens(systemPromptText)
        );

        List<ContextDocumentSegment> contextSegments = buildContextSegments(contextDocs);
        List<ConversationTurnSegment> conversationSegments = buildConversationSegments(history);

        CurrentQuerySegment querySegment = new CurrentQuerySegment(
                latestUserMessage,
                estimateTokens(latestUserMessage)
        );

        StructuredPrompt structuredPrompt = new StructuredPrompt(
                systemSegment,
                contextSegments,
                conversationSegments,
                querySegment
        );

        return new StructuredPromptOutcome(
                structuredPrompt,
                retrievalOutcome.notices(),
                retrievalOutcome.documents()
        );
    }

    /**
     * Builds context document segments from retrieved documents.
     */
    private List<ContextDocumentSegment> buildContextSegments(List<Document> contextDocs) {
        List<ContextDocumentSegment> segments = new ArrayList<>();
        for (int docIndex = 0; docIndex < contextDocs.size(); docIndex++) {
            Document doc = contextDocs.get(docIndex);
            Object urlMetadata = doc.getMetadata().get("url");
            String rawUrl = urlMetadata != null ? urlMetadata.toString() : "";
            String normalizedUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
            String content = doc.getText();

            segments.add(new ContextDocumentSegment(
                    docIndex + 1,
                    normalizedUrl,
                    content,
                    estimateTokens(content)
            ));
        }
        return segments;
    }

    /**
     * Builds conversation turn segments from message history.
     */
    private List<ConversationTurnSegment> buildConversationSegments(List<Message> history) {
        List<ConversationTurnSegment> segments = new ArrayList<>();
        for (Message msg : history) {
            String role;
            String text;
            if (msg instanceof UserMessage userMsg) {
                role = ConversationTurnSegment.ROLE_USER;
                text = userMsg.getText();
            } else if (msg instanceof AssistantMessage assistantMsg) {
                role = ConversationTurnSegment.ROLE_ASSISTANT;
                text = assistantMsg.getText();
            } else {
                continue;
            }
            segments.add(new ConversationTurnSegment(role, text, estimateTokens(text)));
        }
        return segments;
    }

    /**
     * Estimates token count for text using conservative approximation.
     *
     * <p>Uses ~4 characters per token as a safe estimate for English text.
     * This is intentionally conservative to avoid exceeding limits.</p>
     *
     * @param text the text to estimate
     * @return estimated token count
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Conservative: ~4 chars per token, add 1 for rounding
        return (text.length() / 4) + 1;
    }

    /**
     * Determines the quality of search results and provides context to the AI.
     *
     * <p>Delegates to {@link SearchQualityLevel} enum for self-describing quality categorization.</p>
     */
    private String determineSearchQuality(List<Document> docs) {
        return SearchQualityLevel.describeQuality(DocumentContentAdapter.fromDocuments(docs));
    }

    /**
     * Captures a structured prompt with retrieval metadata for intelligent truncation.
     *
     * @param structuredPrompt the typed prompt segments
     * @param notices retrieval notices for UI diagnostics
     * @param documents source documents for citation emission
     */
    public record StructuredPromptOutcome(
            StructuredPrompt structuredPrompt,
            List<RetrievalService.RetrievalNotice> notices,
            List<Document> documents) {
        public StructuredPromptOutcome {
            if (structuredPrompt == null) {
                throw new IllegalArgumentException("Structured prompt cannot be null");
            }
            notices = notices == null ? List.of() : List.copyOf(notices);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        /**
         * Renders the structured prompt to a string for legacy compatibility.
         *
         * @return the complete prompt as a string
         */
        public String prompt() {
            return structuredPrompt.render();
        }
    }
}
