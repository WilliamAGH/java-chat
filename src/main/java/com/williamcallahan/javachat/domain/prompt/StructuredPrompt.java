package com.williamcallahan.javachat.domain.prompt;

import java.util.List;
import java.util.StringJoiner;

/**
 * Aggregates all prompt segments for structure-aware truncation and rendering.
 *
 * <p>Encapsulates the complete prompt structure: system instructions, RAG context,
 * conversation history, and the current query. This enables intelligent truncation
 * that respects semantic boundaries rather than cutting mid-segment.</p>
 *
 * @param system system instructions segment (CRITICAL priority)
 * @param contextDocuments authoritative HIGH or ordinary LOW context documents
 * @param conversationHistory past conversation turns (MEDIUM priority)
 * @param currentQuery the user's current question (HIGH priority)
 * @param turnGuidance application-owned guidance specific to the current turn
 */
public record StructuredPrompt(
        SystemSegment system,
        List<ContextDocumentSegment> contextDocuments,
        List<ConversationTurnSegment> conversationHistory,
        CurrentQuerySegment currentQuery,
        SystemSegment turnGuidance) {

    /** Separator between prompt segments. */
    public static final String SEGMENT_SEPARATOR = "\n\n";

    /** Creates a prompt without turn-specific guidance. */
    public StructuredPrompt(
            SystemSegment system,
            List<ContextDocumentSegment> contextDocuments,
            List<ConversationTurnSegment> conversationHistory,
            CurrentQuerySegment currentQuery) {
        this(system, contextDocuments, conversationHistory, currentQuery, new SystemSegment("", 0));
    }

    /** Keeps turn-specific instructions outside the reusable system prefix. */
    public StructuredPrompt withTurnGuidance(SystemSegment guidance) {
        return new StructuredPrompt(system, contextDocuments, conversationHistory, currentQuery, guidance);
    }

    /**
     * Creates a structured prompt with defensive copies of lists.
     *
     * @throws IllegalArgumentException if system or currentQuery is null
     */
    public StructuredPrompt {
        if (system == null) {
            throw new IllegalArgumentException("System segment cannot be null");
        }
        if (currentQuery == null) {
            throw new IllegalArgumentException("Current query segment cannot be null");
        }
        java.util.Objects.requireNonNull(turnGuidance, "turnGuidance");
        contextDocuments = contextDocuments == null ? List.of() : List.copyOf(contextDocuments);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    /**
     * Calculates total estimated tokens across all segments.
     *
     * @return sum of all segment token estimates
     */
    public int totalEstimatedTokens() {
        int total = system.estimatedTokens() + currentQuery.estimatedTokens() + turnGuidance.estimatedTokens();
        for (ContextDocumentSegment contextDocument : contextDocuments) {
            total += contextDocument.estimatedTokens();
        }
        for (ConversationTurnSegment conversationTurn : conversationHistory) {
            total += conversationTurn.estimatedTokens();
        }
        return total;
    }

    /**
     * Renders the complete prompt to a string for consumers that require one text value.
     *
     * <p>Segments are joined with paragraph separators in order: system instructions,
     * conversation history, turn guidance, context documents, and the current query.</p>
     *
     * @return the fully assembled prompt string
     */
    public String render() {
        return system.content() + SEGMENT_SEPARATOR + renderInput();
    }

    /**
     * Renders the non-system prompt segments as Responses API input.
     *
     * <p>Keeping system instructions separate lets the request boundary submit them through
     * the API's priority-bearing instructions field without duplicating them in user input.</p>
     *
     * @return the assembled context, conversation history, and current query
     */
    public String renderInput() {
        StringJoiner promptInput = new StringJoiner(SEGMENT_SEPARATOR);

        for (ConversationTurnSegment conversationTurn : conversationHistory) {
            promptInput.add(conversationTurn.content());
        }
        if (!turnGuidance.content().isBlank()) {
            promptInput.add(turnGuidance.content());
        }
        for (ContextDocumentSegment contextDocument : contextDocuments) {
            promptInput.add(contextDocument.content());
        }

        promptInput.add(currentQuery.content());

        return promptInput.toString();
    }

    /**
     * Creates a new StructuredPrompt with updated context documents.
     *
     * @param newContextDocuments replacement document list
     * @return new prompt with updated documents
     */
    public StructuredPrompt withContextDocuments(List<ContextDocumentSegment> newContextDocuments) {
        return new StructuredPrompt(system, newContextDocuments, conversationHistory, currentQuery, turnGuidance);
    }

    /**
     * Creates a new StructuredPrompt with updated conversation history.
     *
     * @param newConversationHistory replacement history list
     * @return new prompt with updated history
     */
    public StructuredPrompt withConversationHistory(List<ConversationTurnSegment> newConversationHistory) {
        return new StructuredPrompt(system, contextDocuments, newConversationHistory, currentQuery, turnGuidance);
    }

    /**
     * Creates a minimal StructuredPrompt from a raw prompt string.
     *
     * <p>Used for backward compatibility when only a pre-built string is available.
     * The entire string is treated as the system segment with an empty query.</p>
     *
     * @param rawPrompt the complete prompt as a single string
     * @param estimatedTokens token estimate for the raw prompt
     * @return a minimal structured prompt wrapping the raw string
     */
    public static StructuredPrompt fromRawPrompt(String rawPrompt, int estimatedTokens) {
        return new StructuredPrompt(
                new SystemSegment(rawPrompt, estimatedTokens), List.of(), List.of(), new CurrentQuerySegment("", 0));
    }
}
