package com.williamcallahan.javachat.support;

import java.util.Objects;

/**
 * Owns the invariant for bounded values rendered by Logback structured key-value patterns.
 *
 * <p>Logback quotes structured values without escaping embedded quotes. Removing delimiters,
 * line separators, control characters, and bidirectional formatting characters prevents a
 * field from forging adjacent fields or log lines.</p>
 */
public final class StructuredLogValue {
    private static final String UNKNOWN_LOG_VALUE = "unknown";

    private final String text;

    private StructuredLogValue(String text) {
        this.text = text;
    }

    /**
     * Creates a bounded value that is safe for Logback's quoted key-value rendering.
     *
     * @param sourceValue value supplied by a request, provider, or dependency
     * @param maxCharacters maximum number of UTF-16 characters retained
     * @return sanitized structured log value
     * @throws IllegalArgumentException when {@code maxCharacters} is not positive
     */
    public static StructuredLogValue bounded(Object sourceValue, int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        String sourceText = Objects.toString(sourceValue, UNKNOWN_LOG_VALUE);
        int boundedLength = Math.min(sourceText.length(), maxCharacters);
        StringBuilder safeText = new StringBuilder(boundedLength);
        for (int characterIndex = 0; characterIndex < boundedLength; characterIndex++) {
            char sourceCharacter = sourceText.charAt(characterIndex);
            safeText.append(isUnsafe(sourceCharacter) ? '?' : sourceCharacter);
        }
        return new StructuredLogValue(safeText.toString());
    }

    /** Returns the sanitized text for a structured logging field. */
    public String text() {
        return text;
    }

    private static boolean isUnsafe(char sourceCharacter) {
        int characterType = Character.getType(sourceCharacter);
        return sourceCharacter == '"'
                || sourceCharacter == '\\'
                || Character.isISOControl(sourceCharacter)
                || characterType == Character.FORMAT
                || characterType == Character.LINE_SEPARATOR
                || characterType == Character.PARAGRAPH_SEPARATOR;
    }
}
