package com.williamcallahan.javachat.domain.prompt;

/**
 * Represents a typed segment of a chat prompt with known token cost and truncation priority.
 *
 * <p>Sealed hierarchy ensures exhaustive handling during prompt assembly and truncation.
 * Each segment type carries its content and metadata needed for intelligent truncation
 * that respects semantic boundaries.</p>
 */
public sealed interface PromptSegment
        permits SystemSegment, ContextDocumentSegment, ConversationTurnSegment, CurrentQuerySegment {

    /**
     * Renders the segment to its final prompt string representation.
     *
     * @return formatted content ready for inclusion in the prompt
     */
    String content();

    /**
     * Estimated token count for budget calculations.
     *
     * @return approximate number of tokens this segment will consume
     */
    int estimatedTokens();

    /**
     * Truncation priority determining removal order when budget is exceeded.
     *
     * @return priority level for this segment type
     */
    PromptSegmentPriority priority();
}
