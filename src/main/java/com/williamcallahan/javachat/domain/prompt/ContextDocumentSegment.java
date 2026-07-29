package com.williamcallahan.javachat.domain.prompt;

/**
 * RAG context document segment containing retrieved knowledge for grounding responses.
 *
 * <p>Ordinary retrieval context has LOW priority and is truncated before conversation history.
 * A direct owner may promote authoritative context to HIGH priority. Documents are numbered with
 * [CTX N] markers for citation reference in the model's response.</p>
 *
 * @param index 1-based document index for [CTX N] marker
 * @param documentId stable source-document identity for post-truncation citation fidelity
 * @param sourceUrl normalized URL for citation attribution
 * @param documentContent extracted text content from the source
 * @param estimatedTokens approximate token count for budget calculations
 * @param priority relative importance when the prompt exceeds its token budget
 */
public record ContextDocumentSegment(
        int index,
        String documentId,
        String sourceUrl,
        String documentContent,
        int estimatedTokens,
        PromptSegmentPriority priority)
        implements PromptSegment {

    /** Marker prefix for context document references. */
    public static final String CONTEXT_MARKER = "[CTX ";

    /**
     * Creates a context document segment with validation.
     *
     * @throws IllegalArgumentException if index is less than 1
     * @throws IllegalArgumentException if documentId is null or blank
     */
    public ContextDocumentSegment {
        if (index < 1) {
            throw new IllegalArgumentException("Context document index must be at least 1");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Context document identity cannot be null or blank");
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
        if (priority == null) {
            priority = PromptSegmentPriority.LOW;
        }
        if (priority != PromptSegmentPriority.LOW && priority != PromptSegmentPriority.HIGH) {
            throw new IllegalArgumentException("Context priority must be LOW or HIGH");
        }
    }

    /**
     * Creates ordinary retrieval context that is discarded before conversation history.
     */
    public ContextDocumentSegment(
            int index, String documentId, String sourceUrl, String documentContent, int estimatedTokens) {
        this(index, documentId, sourceUrl, documentContent, estimatedTokens, PromptSegmentPriority.LOW);
    }

    /**
     * Returns this document with an explicit truncation priority.
     *
     * @param priority LOW for ordinary retrieval or HIGH for authoritative context
     * @return copied context segment with the requested priority
     */
    public ContextDocumentSegment withPriority(PromptSegmentPriority priority) {
        return new ContextDocumentSegment(index, documentId, sourceUrl, documentContent, estimatedTokens, priority);
    }

    @Override
    public String content() {
        return CONTEXT_MARKER + index + "] " + sourceUrl + "\n" + documentContent;
    }
}
