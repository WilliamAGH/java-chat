package com.williamcallahan.javachat.domain.prompt;

/**
 * Conversation history segment representing a past user or assistant message.
 *
 * <p>Has MEDIUM priority - older history is truncated after context documents
 * but before the current query. Recent history provides conversation continuity
 * but is less critical than the active question.</p>
 *
 * @param role participant role ("user" or "assistant")
 * @param messageText the message content
 * @param estimatedTokens approximate token count for budget calculations
 */
public record ConversationTurnSegment(
        String role,
        String messageText,
        int estimatedTokens
) implements PromptSegment {

    /** Prefix for assistant messages in the rendered prompt. */
    public static final String ASSISTANT_PREFIX = "Assistant: ";

    /** Role constant for user messages. */
    public static final String ROLE_USER = "user";

    /** Role constant for assistant messages. */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * Creates a conversation turn segment with validation.
     *
     * @throws IllegalArgumentException if role or messageText is null
     */
    public ConversationTurnSegment {
        if (role == null) {
            throw new IllegalArgumentException("Role cannot be null");
        }
        if (messageText == null) {
            throw new IllegalArgumentException("Message text cannot be null");
        }
        if (estimatedTokens < 0) {
            estimatedTokens = 0;
        }
    }

    @Override
    public String content() {
        if (ROLE_ASSISTANT.equals(role)) {
            return ASSISTANT_PREFIX + messageText;
        }
        return messageText;
    }

    @Override
    public PromptSegmentPriority priority() {
        return PromptSegmentPriority.MEDIUM;
    }

    /**
     * Checks if this segment represents a user message.
     *
     * @return true if this is a user turn
     */
    public boolean isUserTurn() {
        return ROLE_USER.equals(role);
    }

    /**
     * Checks if this segment represents an assistant message.
     *
     * @return true if this is an assistant turn
     */
    public boolean isAssistantTurn() {
        return ROLE_ASSISTANT.equals(role);
    }
}
