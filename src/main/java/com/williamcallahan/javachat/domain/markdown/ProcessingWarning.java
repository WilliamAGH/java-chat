package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents a non-fatal warning encountered during markdown processing.
 * Used for structured error reporting instead of silent failures.
 */
public record ProcessingWarning(String message, int position, String context) {

    public ProcessingWarning {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Warning message cannot be null or empty");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Warning position must be non-negative");
        }
    }
}
