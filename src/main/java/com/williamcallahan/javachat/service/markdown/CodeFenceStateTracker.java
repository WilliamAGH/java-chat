package com.williamcallahan.javachat.service.markdown;

/**
 * Tracks state for markdown code fence and inline code span parsing.
 *
 * <p>This is a mutable state machine that tracks whether the parser is currently
 * inside a fenced code block or inline code span. Both {@link EnrichmentPlaceholderizer}
 * and {@link MarkdownNormalizer} require identical fence-tracking logic to avoid
 * processing markers inside code regions.</p>
 *
 * <p>Usage: call {@link #processCharacter(String, int, boolean)} for each character position,
 * then check {@link #isInsideCode()} before processing enrichment markers or list normalization.</p>
 */
final class CodeFenceStateTracker {

    /** Minimum fence length for valid code fences (3 per CommonMark spec). */
    static final int FENCE_MIN_LENGTH = 3;

    /** Default fence character when none specified. */
    static final char DEFAULT_FENCE_CHAR = '`';

    private static final char BACKTICK = '`';
    private static final char TILDE = '~';

    private boolean inFence;
    private char fenceChar;
    private int fenceLength;
    private boolean inInlineCode;
    private int inlineBacktickLength;

    /**
     * Describes a detected fence marker at a position.
     *
     * @param character the fence character (backtick or tilde)
     * @param length number of consecutive fence characters
     */
    record FenceMarker(char character, int length) {}

    /**
     * Describes a detected inline backtick run.
     *
     * @param length number of consecutive backticks
     */
    record BacktickRun(int length) {}

    /**
     * Scans for a fence marker (3+ backticks or tildes) at the given position.
     *
     * @param text source text
     * @param index position to scan from
     * @return fence marker if found, null otherwise
     */
    static FenceMarker scanFenceMarker(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        char markerChar = text.charAt(index);
        if (markerChar != BACKTICK && markerChar != TILDE) {
            return null;
        }
        int length = 0;
        while (index + length < text.length() && text.charAt(index + length) == markerChar) {
            length++;
        }
        return length >= FENCE_MIN_LENGTH ? new FenceMarker(markerChar, length) : null;
    }

    /**
     * Scans for a backtick run (1+ backticks) at the given position.
     *
     * @param text source text
     * @param index position to scan from
     * @return backtick run if found, null otherwise
     */
    static BacktickRun scanBacktickRun(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        if (text.charAt(index) != BACKTICK) {
            return null;
        }
        int length = 0;
        while (index + length < text.length() && text.charAt(index + length) == BACKTICK) {
            length++;
        }
        return new BacktickRun(length);
    }

    /**
     * Checks whether there is a closing backtick run of the specified length after the given position.
     *
     * @param text source text
     * @param startIndex position after the opening backtick run
     * @param runLength length of backticks to match
     * @return true if a matching closing run exists
     */
    static boolean hasClosingBacktickRun(String text, int startIndex, int runLength) {
        int scanIndex = startIndex + runLength;
        while (scanIndex < text.length()) {
            int nextBacktickIndex = text.indexOf(BACKTICK, scanIndex);
            if (nextBacktickIndex < 0) {
                return false;
            }
            int matchLength = 0;
            while (nextBacktickIndex + matchLength < text.length()
                && text.charAt(nextBacktickIndex + matchLength) == BACKTICK) {
                matchLength++;
            }
            if (matchLength == runLength) {
                return true;
            }
            scanIndex = nextBacktickIndex + matchLength;
        }
        return false;
    }

    /**
     * Returns whether the parser is currently inside a fenced code block or inline code span.
     */
    boolean isInsideCode() {
        return inFence || inInlineCode;
    }

    /**
     * Returns whether the parser is inside a fenced code block.
     */
    boolean isInsideFence() {
        return inFence;
    }

    /**
     * Returns whether the parser is inside an inline code span.
     */
    boolean isInsideInlineCode() {
        return inInlineCode;
    }

    /**
     * Processes a character position to update fence and inline code state.
     *
     * @param text the full markdown text
     * @param cursor current position in the text
     * @param isStartOfLine whether this position is at the start of a line
     * @return the number of characters consumed (0 if no state change, &gt;0 if fence/backtick run was processed)
     */
    int processCharacter(String text, int cursor, boolean isStartOfLine) {
        // Check for fence markers at start of line
        if (isStartOfLine) {
            FenceMarker marker = scanFenceMarker(text, cursor);
            if (marker != null) {
                if (!inFence) {
                    inFence = true;
                    fenceChar = marker.character();
                    fenceLength = marker.length();
                } else if (marker.character() == fenceChar && marker.length() >= fenceLength) {
                    inFence = false;
                    fenceChar = 0;
                    fenceLength = 0;
                }
                return marker.length();
            }
        }

        // Track inline code spans outside fenced code
        if (!inFence) {
            BacktickRun backtickRun = scanBacktickRun(text, cursor);
            if (backtickRun != null) {
                if (!inInlineCode && !hasClosingBacktickRun(text, cursor, backtickRun.length())) {
                    return backtickRun.length();
                }
                if (!inInlineCode) {
                    inInlineCode = true;
                    inlineBacktickLength = backtickRun.length();
                } else if (backtickRun.length() == inlineBacktickLength) {
                    inInlineCode = false;
                    inlineBacktickLength = 0;
                }
                return backtickRun.length();
            }
        }

        return 0;
    }

    /**
     * Enters a fence state manually (used when processing fence markers with additional logic).
     */
    void enterFence(char character, int length) {
        this.inFence = true;
        this.fenceChar = character;
        this.fenceLength = length;
    }

    /**
     * Exits the current fence state.
     */
    void exitFence() {
        this.inFence = false;
        this.fenceChar = 0;
        this.fenceLength = 0;
    }

    /**
     * Checks if a fence marker would close the current fence.
     */
    boolean wouldCloseFence(FenceMarker marker) {
        return inFence && marker != null && marker.character() == fenceChar && marker.length() >= fenceLength;
    }

    /**
     * Returns the current fence character, or 0 if not in a fence.
     */
    char getFenceChar() {
        return fenceChar;
    }

    /**
     * Returns the current fence length, or 0 if not in a fence.
     */
    int getFenceLength() {
        return fenceLength;
    }

    /**
     * Resets all state to initial values.
     */
    void reset() {
        inFence = false;
        fenceChar = 0;
        fenceLength = 0;
        inInlineCode = false;
        inlineBacktickLength = 0;
    }
}
