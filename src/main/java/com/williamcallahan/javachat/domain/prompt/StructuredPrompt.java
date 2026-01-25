package com.williamcallahan.javachat.domain.prompt;

import java.util.List;

/**
 * Aggregates all prompt segments for structure-aware truncation and rendering.
 *
 * <p>Encapsulates the complete prompt structure: system instructions, RAG context,
 * conversation history, and the current query. This enables intelligent truncation
 * that respects semantic boundaries rather than cutting mid-segment.</p>
 *
 * @param system system instructions segment (CRITICAL priority)
 * @param contextDocuments retrieved RAG documents (LOW priority, truncated first)
 * @param conversationHistory past conversation turns (MEDIUM priority)
 * @param currentQuery the user's current question (HIGH priority)
 */
public record StructuredPrompt(
        SystemSegment system,
        List<ContextDocumentSegment> contextDocuments,
        List<ConversationTurnSegment> conversationHistory,
        CurrentQuerySegment currentQuery) {

    /** Separator between prompt segments. */
    public static final String SEGMENT_SEPARATOR = "\n\n";

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
        contextDocuments = contextDocuments == null ? List.of() : List.copyOf(contextDocuments);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    /**
     * Calculates total estimated tokens across all segments.
     *
     * @return sum of all segment token estimates
     */
    public int totalEstimatedTokens() {
        int total = system.estimatedTokens() + currentQuery.estimatedTokens();
        for (ContextDocumentSegment doc : contextDocuments) {
            total += doc.estimatedTokens();
        }
        for (ConversationTurnSegment turn : conversationHistory) {
            total += turn.estimatedTokens();
        }
        return total;
    }

    /**
     * Renders the complete prompt to a string for LLM submission.
     *
     * <p>Segments are joined with paragraph separators in order:
     * system → context documents → conversation history → current query.</p>
     *
     * @return the fully assembled prompt string
     */
    public String render() {
        StringBuilder prompt = new StringBuilder(system.content());

        for (ContextDocumentSegment doc : contextDocuments) {
            prompt.append(SEGMENT_SEPARATOR).append(doc.content());
        }

        for (ConversationTurnSegment turn : conversationHistory) {
            prompt.append(SEGMENT_SEPARATOR).append(turn.content());
        }

        prompt.append(SEGMENT_SEPARATOR).append(currentQuery.content());

        return prompt.toString();
    }

    /**
     * Creates a new StructuredPrompt with updated context documents.
     *
     * @param newContextDocuments replacement document list
     * @return new prompt with updated documents
     */
    public StructuredPrompt withContextDocuments(List<ContextDocumentSegment> newContextDocuments) {
        return new StructuredPrompt(system, newContextDocuments, conversationHistory, currentQuery);
    }

    /**
     * Creates a new StructuredPrompt with updated conversation history.
     *
     * @param newConversationHistory replacement history list
     * @return new prompt with updated history
     */
    public StructuredPrompt withConversationHistory(List<ConversationTurnSegment> newConversationHistory) {
        return new StructuredPrompt(system, contextDocuments, newConversationHistory, currentQuery);
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
