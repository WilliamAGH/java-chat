package com.williamcallahan.javachat.domain.prompt;

/**
 * Defines truncation priority for prompt segments during token budget management.
 *
 * <p>Lower priority segments are truncated first when the total prompt exceeds
 * model token limits. This preserves the most important content: the user's
 * current query and system instructions.</p>
 */
public enum PromptSegmentPriority {

    /**
     * Never truncated - system instructions essential for model behavior.
     */
    CRITICAL,

    /**
     * Truncated last - the user's current question must be preserved.
     */
    HIGH,

    /**
     * Truncated after context - recent conversation provides continuity.
     */
    MEDIUM,

    /**
     * Truncated first - RAG context documents are supplementary.
     */
    LOW
}
