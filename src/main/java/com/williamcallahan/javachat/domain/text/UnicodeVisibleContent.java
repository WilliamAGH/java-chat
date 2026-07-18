package com.williamcallahan.javachat.domain.text;

/**
 * Defines the visible-character boundary used by user-entered and rendered text.
 *
 * <p>Java's {@link String#isBlank()} deliberately leaves several format and non-breaking
 * characters visible to validation. Those characters cannot produce meaningful chat or
 * enrichment content, so this policy treats them as blank alongside Java whitespace and
 * Unicode space separators.</p>
 */
public final class UnicodeVisibleContent {

    private static final int ZERO_WIDTH_SPACE = 0x200B;
    private static final int ZERO_WIDTH_NO_BREAK_SPACE = 0xFEFF;
    private static final int WORD_JOINER = 0x2060;

    private UnicodeVisibleContent() {}

    /**
     * Determines whether text contains a character that can provide visible content.
     *
     * @param candidateText text to evaluate; null has no visible content
     * @return true when at least one code point is not a blank-format or whitespace character
     */
    public static boolean hasVisibleContent(String candidateText) {
        return candidateText != null && candidateText.codePoints().anyMatch(UnicodeVisibleContent::isVisible);
    }

    private static boolean isVisible(int codePoint) {
        return !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)
                && codePoint != ZERO_WIDTH_SPACE
                && codePoint != ZERO_WIDTH_NO_BREAK_SPACE
                && codePoint != WORD_JOINER;
    }
}
