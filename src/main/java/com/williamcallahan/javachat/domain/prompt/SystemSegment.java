package com.williamcallahan.javachat.domain.prompt;

/**
 * System instructions segment containing the core prompt that shapes model behavior.
 *
 * <p>Always has CRITICAL priority - never truncated as it defines how the model
 * should respond, including citation requirements and Java-learning focus.</p>
 *
 * @param systemPrompt the complete system instruction text
 * @param estimatedTokens approximate token count for budget calculations
 */
public record SystemSegment(
        String systemPrompt,
        int estimatedTokens
) implements PromptSegment {

    /**
     * Creates a system segment with validation.
     *
     * @throws IllegalArgumentException if systemPrompt is null
     */
    public SystemSegment {
        if (systemPrompt == null) {
            throw new IllegalArgumentException("System prompt cannot be null");
        }
        if (estimatedTokens < 0) {
            estimatedTokens = 0;
        }
    }

    @Override
    public String content() {
        return systemPrompt;
    }

    @Override
    public PromptSegmentPriority priority() {
        return PromptSegmentPriority.CRITICAL;
    }
}
