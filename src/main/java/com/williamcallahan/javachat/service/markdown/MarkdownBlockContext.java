package com.williamcallahan.javachat.service.markdown;

import java.util.Optional;

/**
 * Classifies Markdown lines and delimiters that establish code-only regions.
 *
 * <p>Both normalization and enrichment extraction must make the same distinction between
 * visible Markdown and code. The classifier recognizes CommonMark's zero-to-three-space fence
 * allowance, indented code, fenced-code closure rules, and multiline inline-code spans.</p>
 */
final class MarkdownBlockContext {

    static final int FENCE_MINIMUM_LENGTH = 3;
    static final int MAX_FENCE_INDENTATION_SPACES = 3;
    static final int INDENTED_CODE_INDENTATION_SPACES = 4;
    static final char DEFAULT_FENCE_CHARACTER = '`';

    private static final char BACKTICK = '`';
    private static final char TILDE = '~';

    private boolean insideFencedCodeBlock;
    private char fenceCharacter;
    private int fenceLength;
    private boolean insideInlineCode;
    private int inlineBacktickLength;

    /** Describes the block context that owns an entire Markdown line. */
    enum LineContext {
        TEXT,
        FENCED_CODE,
        INDENTED_CODE;

        boolean isCodeBlock() {
            return this != TEXT;
        }
    }

    /**
     * Describes a fence delimiter in a Markdown line.
     *
     * @param startIndex source index of the first delimiter character
     * @param character delimiter character
     * @param length number of repeated delimiter characters
     */
    record FenceMarker(int startIndex, char character, int length) {
        int endIndex() {
            return startIndex + length;
        }
    }

    /**
     * Classifies a line and advances fenced-code state across a fence transition.
     *
     * <p>The returned context describes the supplied line even when that line closes a fence.
     * A four-space line is always indented code while outside a fenced block.</p>
     *
     * @param markdown source Markdown
     * @param lineStartIndex inclusive source index for the line
     * @param lineEndIndex exclusive source index for the line, before its line ending
     * @return context that owns the entire line
     */
    LineContext classifyLine(String markdown, int lineStartIndex, int lineEndIndex) {
        if (insideInlineCode) {
            return LineContext.TEXT;
        }

        Optional<FenceMarker> fenceMarker = scanFenceAtBlockIndentation(markdown, lineStartIndex, lineEndIndex);
        if (insideFencedCodeBlock) {
            fenceMarker
                    .filter(marker -> closesCurrentFence(marker, markdown, lineEndIndex))
                    .ifPresent(marker -> exitFencedCodeBlock());
            return LineContext.FENCED_CODE;
        }

        if (indentationColumns(markdown, lineStartIndex, lineEndIndex) >= INDENTED_CODE_INDENTATION_SPACES) {
            return LineContext.INDENTED_CODE;
        }

        Optional<FenceMarker> openingFenceMarker =
                fenceMarker.filter(marker -> canOpenFence(marker, markdown, lineEndIndex));
        openingFenceMarker.ifPresent(this::enterFencedCodeBlock);
        return openingFenceMarker.isPresent() ? LineContext.FENCED_CODE : LineContext.TEXT;
    }

    /**
     * Consumes an inline-code backtick delimiter at a cursor position.
     *
     * @param markdown source Markdown
     * @param cursor source index to inspect
     * @return delimiter length when the cursor advances inline-code state, otherwise zero
     */
    int consumeInlineCodeDelimiter(String markdown, int cursor) {
        if (insideFencedCodeBlock) {
            return 0;
        }

        int delimiterLength = backtickRunLength(markdown, cursor);
        if (delimiterLength == 0) {
            return 0;
        }

        if (!insideInlineCode) {
            if (!hasClosingBacktickRun(markdown, cursor + delimiterLength, delimiterLength)) {
                return 0;
            }
            insideInlineCode = true;
            inlineBacktickLength = delimiterLength;
            return delimiterLength;
        }

        if (delimiterLength == inlineBacktickLength) {
            insideInlineCode = false;
            inlineBacktickLength = 0;
        }
        return delimiterLength;
    }

    /**
     * Advances inline-code state through a non-code line.
     *
     * @param markdown source Markdown
     * @param startIndex inclusive line start
     * @param endIndex exclusive line end
     */
    void consumeInlineCodeDelimiters(String markdown, int startIndex, int endIndex) {
        int cursor = startIndex;
        while (cursor < endIndex) {
            int delimiterLength = consumeInlineCodeDelimiter(markdown, cursor);
            cursor += delimiterLength == 0 ? 1 : delimiterLength;
        }
    }

    /**
     * Returns whether a code region owns the current cursor position.
     */
    boolean isInsideCode() {
        return insideFencedCodeBlock || insideInlineCode;
    }

    /**
     * Returns whether an inline code span is active.
     */
    boolean isInsideInlineCode() {
        return insideInlineCode;
    }

    /**
     * Returns whether a fenced code block is active.
     */
    boolean isInsideFencedCodeBlock() {
        return insideFencedCodeBlock;
    }

    /**
     * Returns whether a marker matches the active fence without requiring closing-line trivia.
     */
    boolean matchesCurrentFence(FenceMarker marker) {
        return insideFencedCodeBlock && marker.character() == fenceCharacter && marker.length() >= fenceLength;
    }

    /**
     * Builds a closing fence for a currently open code block.
     */
    String closingFence() {
        char closingCharacter = fenceCharacter == 0 ? DEFAULT_FENCE_CHARACTER : fenceCharacter;
        int closingLength = Math.max(FENCE_MINIMUM_LENGTH, fenceLength);
        return String.valueOf(closingCharacter).repeat(closingLength);
    }

    /**
     * Finds the exclusive line end before an optional newline character.
     */
    static int lineEndIndex(String markdown, int lineStartIndex) {
        int lineEndIndex = lineStartIndex;
        while (lineEndIndex < markdown.length() && markdown.charAt(lineEndIndex) != '\n') {
            lineEndIndex++;
        }
        return lineEndIndex;
    }

    /**
     * Scans a valid fence marker at a supplied source position.
     */
    static Optional<FenceMarker> scanFenceMarker(String markdown, int markerStartIndex, int lineEndIndex) {
        if (markerStartIndex < 0 || markerStartIndex >= lineEndIndex) {
            return Optional.empty();
        }
        char markerCharacter = markdown.charAt(markerStartIndex);
        if (markerCharacter != BACKTICK && markerCharacter != TILDE) {
            return Optional.empty();
        }

        int markerLength = 0;
        while (markerStartIndex + markerLength < lineEndIndex
                && markdown.charAt(markerStartIndex + markerLength) == markerCharacter) {
            markerLength++;
        }
        if (markerLength < FENCE_MINIMUM_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new FenceMarker(markerStartIndex, markerCharacter, markerLength));
    }

    /**
     * Scans a valid fence marker after no more than CommonMark's three leading spaces.
     */
    static Optional<FenceMarker> scanFenceAtBlockIndentation(String markdown, int lineStartIndex, int lineEndIndex) {
        int markerStartIndex = lineStartIndex;
        while (markerStartIndex < lineEndIndex && markdown.charAt(markerStartIndex) == ' ') {
            markerStartIndex++;
        }
        if (markerStartIndex - lineStartIndex > MAX_FENCE_INDENTATION_SPACES) {
            return Optional.empty();
        }
        return scanFenceMarker(markdown, markerStartIndex, lineEndIndex);
    }

    private boolean closesCurrentFence(FenceMarker marker, String markdown, int lineEndIndex) {
        return matchesCurrentFence(marker) && hasOnlySpaceOrTab(markdown, marker.endIndex(), lineEndIndex);
    }

    private boolean canOpenFence(FenceMarker marker, String markdown, int lineEndIndex) {
        if (marker.character() != BACKTICK) {
            return true;
        }
        for (int cursor = marker.endIndex(); cursor < lineEndIndex; cursor++) {
            if (markdown.charAt(cursor) == BACKTICK) {
                return false;
            }
        }
        return true;
    }

    private void enterFencedCodeBlock(FenceMarker marker) {
        insideFencedCodeBlock = true;
        fenceCharacter = marker.character();
        fenceLength = marker.length();
    }

    private void exitFencedCodeBlock() {
        insideFencedCodeBlock = false;
        fenceCharacter = 0;
        fenceLength = 0;
    }

    private int indentationColumns(String markdown, int lineStartIndex, int lineEndIndex) {
        int indentationColumns = 0;
        for (int cursor = lineStartIndex; cursor < lineEndIndex; cursor++) {
            char currentCharacter = markdown.charAt(cursor);
            if (currentCharacter == ' ') {
                indentationColumns++;
            } else if (currentCharacter == '\t') {
                indentationColumns +=
                        INDENTED_CODE_INDENTATION_SPACES - (indentationColumns % INDENTED_CODE_INDENTATION_SPACES);
            } else {
                return indentationColumns;
            }
        }
        return indentationColumns;
    }

    private int backtickRunLength(String markdown, int cursor) {
        if (cursor < 0 || cursor >= markdown.length() || markdown.charAt(cursor) != BACKTICK) {
            return 0;
        }
        int delimiterLength = 0;
        while (cursor + delimiterLength < markdown.length() && markdown.charAt(cursor + delimiterLength) == BACKTICK) {
            delimiterLength++;
        }
        return delimiterLength;
    }

    private boolean hasClosingBacktickRun(String markdown, int scanStartIndex, int requiredLength) {
        int scanIndex = scanStartIndex;
        while (scanIndex < markdown.length()) {
            int nextBacktickIndex = markdown.indexOf(BACKTICK, scanIndex);
            if (nextBacktickIndex < 0) {
                return false;
            }
            int candidateLength = backtickRunLength(markdown, nextBacktickIndex);
            if (candidateLength == requiredLength) {
                return true;
            }
            scanIndex = nextBacktickIndex + candidateLength;
        }
        return false;
    }

    private boolean hasOnlySpaceOrTab(String markdown, int startIndex, int endIndex) {
        for (int cursor = startIndex; cursor < endIndex; cursor++) {
            char currentCharacter = markdown.charAt(cursor);
            if (currentCharacter != ' ' && currentCharacter != '\t') {
                return false;
            }
        }
        return true;
    }
}
