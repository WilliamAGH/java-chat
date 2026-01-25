package com.williamcallahan.javachat.domain.prompt;

/**
 * RAG context document segment containing retrieved knowledge for grounding responses.
 *
 * <p>Has LOW priority - these are truncated first when budget is exceeded since
 * they are supplementary to the conversation. Documents are numbered with [CTX N]
 * markers for citation reference in the model's response.</p>
 *
 * @param index 1-based document index for [CTX N] marker
 * @param sourceUrl normalized URL for citation attribution
 * @param documentContent extracted text content from the source
 * @param estimatedTokens approximate token count for budget calculations
 */
public record ContextDocumentSegment(
        int index,
        String sourceUrl,
        String documentContent,
        int estimatedTokens
) implements PromptSegment {

    /** Marker prefix for context document references. */
    public static final String CONTEXT_MARKER = "[CTX ";

    /**
     * Creates a context document segment with validation.
     *
     * @throws IllegalArgumentException if index is less than 1
     */
    public ContextDocumentSegment {
        if (index < 1) {
            throw new IllegalArgumentException("Context document index must be at least 1");
        }
        if (sourceUrl == null) {
            sourceUrl = "";
        }
        if (documentContent == null) {
            documentContent = "";
        }
        if (estimatedTokens < 0) {
            estimatedTokens = 0;
        }
    }

    @Override
    public String content() {
        return CONTEXT_MARKER + index + "] " + sourceUrl + "\n" + documentContent;
    }

    @Override
    public PromptSegmentPriority priority() {
        return PromptSegmentPriority.LOW;
    }
}
