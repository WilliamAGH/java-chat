package com.williamcallahan.javachat.model;

import java.util.Objects;

/**
 * Represents a single turn in a chat conversation.
 */
public class ChatTurn {
    private static final String DEFAULT_ROLE = "user";
    private static final String DEFAULT_TEXT = "";

    private String role; // "user" or "assistant"
    private String text;

    /**
     * Creates an empty chat turn.
     */
    public ChatTurn() {
        this.role = DEFAULT_ROLE;
        this.text = DEFAULT_TEXT;
    }

    /**
     * Creates a chat turn with the provided role and text.
     *
     * @param role participant role (for example, "user" or "assistant")
     * @param text message content
     */
    public ChatTurn(String role, String text) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.text = Objects.requireNonNull(text, "text must not be null");
    }

    /**
     * Returns the participant role for this turn.
     *
     * @return role name
     */
    public String getRole() {
        return role;
    }

    /**
     * Updates the participant role for this turn.
     *
     * @param role role name
     */
    public void setRole(String role) {
        this.role = Objects.requireNonNull(role, "role must not be null");
    }

    /**
     * Returns the message text for this turn.
     *
     * @return message content
     */
    public String getText() {
        return text;
    }

    /**
     * Updates the message text for this turn.
     *
     * @param text message content
     */
    public void setText(String text) {
        this.text = Objects.requireNonNull(text, "text must not be null");
    }
}




