package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.SearchQualityLevel;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.CurrentQuerySegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.domain.prompt.SystemSegment;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.support.DocumentContentAdapter;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Builds chat prompts, enriches them with retrieval context, and delegates streaming to the LLM provider.
 */
@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final String UNSPECIFIED_SOURCE_RECORD_FIELD = "unspecified";

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
    public ChatService(
            OpenAIStreamingService openAIStreamingService,
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
    public Flux<String> streamAnswerWithContext(
            List<Message> history, String latestUserMessage, List<Document> contextDocs, String guidance) {
        if (contextDocs == null) contextDocs = List.of();

        StructuredPrompt structuredPrompt =
                buildStructuredPromptWithContextAndGuidance(history, latestUserMessage, contextDocs, guidance);

        return openAIStreamingService
                .streamResponse(structuredPrompt, temperature)
                .flatMapMany(streamingResult -> streamingResult.textChunks());
    }

    /**
     * Resolves citations through official sparse documentation discovery.
     */
    public List<Citation> citationsFor(String userQuery) {
        RetrievalService.CitationOutcome citationOutcome =
                retrievalService.discoverCitations(userQuery, officialDocumentationConstraint());
        return citationOutcome.citationsOrThrow();
    }

    /**
     * Builds citations from the exact prompt context retained by provider-specific truncation.
     *
     * @param userQuery current user query
     * @param promptOutcome prompt and its pre-truncation context documents
     * @param retainedDocumentIds source identities retained after provider truncation
     * @return citations and conversion failures for retained prompt context only
     */
    public RetrievalService.CitationOutcome citationOutcomeForRetainedContext(
            String userQuery, StructuredPromptOutcome promptOutcome, List<String> retainedDocumentIds) {
        return retrievalService.toCitationsForRetainedContext(
                userQuery, promptOutcome.documents(), retainedDocumentIds);
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
            List<Message> history, String latestUserMessage, List<Document> contextDocs, String guidance) {

        // Build system prompt with guidance
        String basePrompt = systemPromptConfig.getCoreSystemPrompt();
        String completePrompt = guidance != null && !guidance.isBlank()
                ? systemPromptConfig.buildFullPrompt(basePrompt, guidance)
                : basePrompt;

        SystemSegment systemSegment = new SystemSegment(completePrompt, estimateTokens(completePrompt));

        List<ContextDocumentSegment> contextSegments =
                buildContextSegments(contextDocs != null ? contextDocs : List.of(), latestUserMessage);

        List<ConversationTurnSegment> conversationSegments = buildConversationSegments(history);

        CurrentQuerySegment querySegment =
                new CurrentQuerySegment(latestUserMessage, estimateTokens(latestUserMessage));

        return new StructuredPrompt(systemSegment, contextSegments, conversationSegments, querySegment);
    }

    /**
     * Builds a structured prompt with retrieval context for intelligent truncation.
     *
     * <p>Returns a StructuredPrompt that can be truncated segment-by-segment
     * rather than character-by-character, preserving semantic boundaries.</p>
     *
     * @param history existing chat history
     * @param latestUserMessage user query
     * @return structured prompt outcome with segments and retrieval metadata
     */
    public StructuredPromptOutcome buildStructuredPromptWithContextOutcome(
            List<Message> history, String latestUserMessage) {
        return buildStructuredPromptWithContextOutcome(history, latestUserMessage, notice -> {});
    }

    /**
     * Builds a context-augmented structured prompt while reporting live retrieval progress.
     *
     * @param history existing chat history
     * @param latestUserMessage user query
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @return structured prompt outcome with segments and retrieval metadata
     */
    public StructuredPromptOutcome buildStructuredPromptWithContextOutcome(
            List<Message> history,
            String latestUserMessage,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener) {
        return buildStructuredPromptWithContextOutcome(
                history,
                latestUserMessage,
                retrievalProgressListener,
                System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos());
    }

    /**
     * Builds a context-augmented prompt within the caller-owned response-preparation deadline.
     *
     * @param history existing chat history
     * @param latestUserMessage user query
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return structured prompt outcome with segments and retrieval metadata
     */
    public StructuredPromptOutcome buildStructuredPromptWithContextOutcome(
            List<Message> history,
            String latestUserMessage,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener,
            long stageDeadlineNanos) {

        List<Document> contextDocs = retrieveTokenConstrainedOfficialDocumentation(
                latestUserMessage, retrievalProgressListener, stageDeadlineNanos);
        logger.debug(
                "Using GPT-5.4 retrieval context: {} documents with max {} tokens each",
                contextDocs.size(),
                ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED);

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
        SystemSegment systemSegment = new SystemSegment(systemPromptText, estimateTokens(systemPromptText));

        List<ContextDocumentSegment> contextSegments = buildContextSegments(contextDocs, latestUserMessage);
        List<ConversationTurnSegment> conversationSegments = buildConversationSegments(history);

        CurrentQuerySegment querySegment =
                new CurrentQuerySegment(latestUserMessage, estimateTokens(latestUserMessage));

        StructuredPrompt structuredPrompt =
                new StructuredPrompt(systemSegment, contextSegments, conversationSegments, querySegment);

        return new StructuredPromptOutcome(structuredPrompt, contextDocs);
    }

    /**
     * Retrieves the official documentation context shared by constrained chat prompts and diagnostics.
     *
     * @param query learner query
     * @return constrained official documents
     */
    public List<Document> retrieveTokenConstrainedOfficialDocumentation(String query) {
        return retrieveTokenConstrainedOfficialDocumentation(query, notice -> {});
    }

    /**
     * Retrieves the official documentation context while reporting live retrieval progress.
     *
     * @param query learner query
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @return constrained official documents
     */
    public List<Document> retrieveTokenConstrainedOfficialDocumentation(
            String query, Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener) {
        return retrieveTokenConstrainedOfficialDocumentation(
                query,
                retrievalProgressListener,
                System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos());
    }

    /**
     * Retrieves constrained official documentation within the caller-owned deadline.
     *
     * @param query learner query
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return constrained official documents
     */
    public List<Document> retrieveTokenConstrainedOfficialDocumentation(
            String query,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener,
            long stageDeadlineNanos) {
        return retrievalService.retrieveWithLimit(
                query,
                ModelConfiguration.RAG_LIMIT_CONSTRAINED,
                ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED,
                officialDocumentationConstraint(),
                retrievalProgressListener,
                stageDeadlineNanos);
    }

    private static RetrievalConstraint officialDocumentationConstraint() {
        return RetrievalConstraint.forOfficialDocSets(DocsSourceRegistry.officialDocumentationSourceIdentities());
    }

    /**
     * Builds context document segments from retrieved documents.
     */
    private List<ContextDocumentSegment> buildContextSegments(
            List<Document> contextDocuments, String latestUserMessage) {
        List<ContextDocumentSegment> segments = new ArrayList<>();
        List<String> requestedJavaVersions = QueryVersionExtractor.extractVersionNumbers(latestUserMessage);
        List<DocsSourceRegistry.VersionedDocumentationEvidence> dependencyEvidence =
                DocsSourceRegistry.versionedDocumentationEvidenceAll(latestUserMessage);
        for (int documentIndex = 0; documentIndex < contextDocuments.size(); documentIndex++) {
            Document document = contextDocuments.get(documentIndex);
            String rawUrl = DocumentFactory.metadataText(document, QdrantPayloadFieldSchema.URL_FIELD);
            String normalizedUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
            String sourceName = DocumentFactory.metadataText(document, QdrantPayloadFieldSchema.SOURCE_NAME_FIELD)
                    .trim();
            String documentationSet = DocumentFactory.metadataText(document, QdrantPayloadFieldSchema.DOC_SET_FIELD)
                    .trim();
            String registeredSourceFamily = DocsSourceRegistry.documentationSourceFamily(documentationSet);
            String sourceFamily = registeredSourceFamily.isBlank()
                    ? (sourceName.isBlank() ? documentationSet : sourceName)
                    : registeredSourceFamily;
            if (sourceFamily.isBlank()) {
                sourceFamily = UNSPECIFIED_SOURCE_RECORD_FIELD;
            }
            String sourceVersion = DocumentFactory.metadataText(document, QdrantPayloadFieldSchema.DOC_VERSION_FIELD)
                    .trim();
            if (sourceVersion.isBlank()) {
                sourceVersion = UNSPECIFIED_SOURCE_RECORD_FIELD;
            }
            String resolvedSourceFamily = sourceFamily;
            String resolvedSourceVersion = sourceVersion;
            List<String> adjacentRequestedVersions = "java".equals(resolvedSourceFamily)
                    ? requestedJavaVersions.stream()
                            .filter(requestedVersion -> !requestedVersion.equals(resolvedSourceVersion))
                            .filter(requestedVersion ->
                                    DocsSourceRegistry.javaApiDocumentationSourcesForRelease(requestedVersion).stream()
                                            .anyMatch(source ->
                                                    source.javaRelease().equals(resolvedSourceVersion)))
                            .toList()
                    : dependencyEvidence.stream()
                            .filter(evidence -> evidence.sourceFamily().equals(resolvedSourceFamily))
                            .filter(evidence -> !evidence.requestedVersion().equals(resolvedSourceVersion))
                            .filter(evidence -> evidence.sources().stream()
                                    .anyMatch(source -> source.docSet().equals(documentationSet)
                                            && source.docVersion().equals(resolvedSourceVersion)))
                            .map(DocsSourceRegistry.VersionedDocumentationEvidence::requestedVersion)
                            .toList();
            String adjacentEvidenceFields = adjacentRequestedVersions.isEmpty()
                    ? ""
                    : " requestedVersions=\""
                            + String.join(",", adjacentRequestedVersions)
                            + "\" evidenceRelation=\"adjacent-same-family\"";
            String sourceRecordHeader = "[SOURCE RECORD family=\""
                    + sourceFamily
                    + "\" version=\""
                    + sourceVersion
                    + "\""
                    + adjacentEvidenceFields
                    + "]";
            String documentContent = document.getText();
            String documentText = sourceRecordHeader + "\n" + (documentContent == null ? "" : documentContent);

            segments.add(new ContextDocumentSegment(
                    documentIndex + 1, document.getId(), normalizedUrl, documentText, estimateTokens(documentText)));
        }
        return segments;
    }

    /**
     * Builds conversation turn segments from message history.
     */
    private List<ConversationTurnSegment> buildConversationSegments(List<Message> history) {
        List<ConversationTurnSegment> segments = new ArrayList<>();
        for (Message historyMessage : history) {
            String role;
            String text;
            if (historyMessage instanceof UserMessage userMessage) {
                role = ConversationTurnSegment.ROLE_USER;
                text = userMessage.getText();
            } else if (historyMessage instanceof AssistantMessage assistantMsg) {
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
        return (text.length() / ModelConfiguration.ESTIMATED_CHARS_PER_TOKEN) + 1;
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
     * @param documents source documents for citation emission
     */
    public record StructuredPromptOutcome(StructuredPrompt structuredPrompt, List<Document> documents) {
        public StructuredPromptOutcome {
            if (structuredPrompt == null) {
                throw new IllegalArgumentException("Structured prompt cannot be null");
            }
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
