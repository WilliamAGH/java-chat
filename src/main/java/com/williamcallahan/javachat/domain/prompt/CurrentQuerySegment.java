package com.williamcallahan.javachat.domain.prompt;

/**
 * Current user query segment containing the active question being answered.
 *
 * <p>Has HIGH priority - this is the user's actual question and should never
 * be truncated unless absolutely necessary. The model needs this to understand
 * what the user is asking.</p>
 *
 * @param queryText the user's current question
 * @param estimatedTokens approximate token count for budget calculations
 */
public record CurrentQuerySegment(
        String queryText,
        int estimatedTokens
) implements PromptSegment {

    /**
     * Creates a current query segment with validation.
     *
     * @throws IllegalArgumentException if queryText is null
     */
    public CurrentQuerySegment {
        if (queryText == null) {
            throw new IllegalArgumentException("Query text cannot be null");
        }
        if (estimatedTokens < 0) {
            estimatedTokens = 0;
        }
    }

    @Override
    public String content() {
        return queryText;
    }

    @Override
    public PromptSegmentPriority priority() {
        return PromptSegmentPriority.HIGH;
    }
}
