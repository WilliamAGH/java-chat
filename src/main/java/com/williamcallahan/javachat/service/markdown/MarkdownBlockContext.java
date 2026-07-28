package com.williamcallahan.javachat.service.markdown;

import java.util.ArrayList;
import java.util.List;
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
    private Optional<FenceMarker> currentFenceMarker = Optional.empty();
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

        boolean usesTilde() {
            return character == TILDE;
        }
    }

    /**
     * Indexes block-indented fence markers for logarithmic forward matching.
     *
     * <p>Backtick and tilde markers remain in separate source-ordered sequences. Each sequence
     * stores maximum marker lengths in segment trees, allowing a query to skip every range that
     * cannot close its opening fence.</p>
     */
    static final class FenceIndex {

        private final FenceSequence backtickFences;
        private final FenceSequence tildeFences;

        /**
         * Builds an index with one forward scan of the Markdown source.
         *
         * @param markdown source Markdown
         */
        FenceIndex(String markdown) {
            this(markdown, (sourceMarkdown, indexedFence) -> true);
        }

        /**
         * Builds an index containing only fences accepted by a boundary-local qualifier.
         *
         * @param markdown source Markdown
         * @param fenceQualifier boundary rule selecting queryable fences
         */
        FenceIndex(String markdown, FenceQualifier fenceQualifier) {
            List<IndexedFence> indexedBacktickFences = new ArrayList<>();
            List<IndexedFence> indexedTildeFences = new ArrayList<>();
            int lineStartIndex = 0;
            while (lineStartIndex < markdown.length()) {
                int currentLineStartIndex = lineStartIndex;
                int lineEndIndex = lineEndIndex(markdown, currentLineStartIndex);
                scanFenceAtBlockIndentation(markdown, currentLineStartIndex, lineEndIndex)
                        .map(marker -> new IndexedFence(
                                marker,
                                currentLineStartIndex,
                                lineEndIndex,
                                hasOnlySpaceOrTab(markdown, marker.endIndex(), lineEndIndex)))
                        .filter(indexedFence -> fenceQualifier.includes(markdown, indexedFence))
                        .ifPresent(indexedFence -> sequenceFor(
                                        indexedFence.marker().character(), indexedBacktickFences, indexedTildeFences)
                                .add(indexedFence));
                lineStartIndex = lineEndIndex + 1;
            }
            backtickFences = new FenceSequence(indexedBacktickFences);
            tildeFences = new FenceSequence(indexedTildeFences);
        }

        @FunctionalInterface
        interface FenceQualifier {
            boolean includes(String markdown, IndexedFence indexedFence);
        }

        /**
         * Finds the earliest qualifying marker at or after a source position.
         *
         * @param searchStartIndex inclusive source position
         * @param openingFenceMarker opening marker that determines character and minimum length
         * @param standaloneOnly whether trailing non-whitespace disqualifies a marker
         * @return earliest matching indexed marker
         */
        Optional<IndexedFence> firstMatching(
                int searchStartIndex, FenceMarker openingFenceMarker, boolean standaloneOnly) {
            return sequenceFor(openingFenceMarker.character())
                    .firstMatching(searchStartIndex, openingFenceMarker.length(), standaloneOnly);
        }

        private FenceSequence sequenceFor(char fenceCharacter) {
            return fenceCharacter == BACKTICK ? backtickFences : tildeFences;
        }

        private static List<IndexedFence> sequenceFor(
                char fenceCharacter, List<IndexedFence> indexedBacktickFences, List<IndexedFence> indexedTildeFences) {
            return fenceCharacter == BACKTICK ? indexedBacktickFences : indexedTildeFences;
        }

        /**
         * Describes an indexed block-indented fence and its source line.
         *
         * @param marker fence delimiter
         * @param lineStartIndex inclusive source line start
         * @param lineEndIndex exclusive source line end
         * @param standalone whether only spaces or tabs follow the marker
         */
        record IndexedFence(FenceMarker marker, int lineStartIndex, int lineEndIndex, boolean standalone) {}

        private static final class FenceSequence {

            private final IndexedFence[] indexedFences;
            private final int segmentTreeLeafCount;
            private final int[] maximumLengths;
            private final int[] standaloneMaximumLengths;

            private FenceSequence(List<IndexedFence> indexedFences) {
                this.indexedFences = indexedFences.toArray(IndexedFence[]::new);
                int requiredLeafCount = Math.max(1, indexedFences.size());
                int leafCount = 1;
                while (leafCount < requiredLeafCount) {
                    leafCount *= 2;
                }
                segmentTreeLeafCount = leafCount;
                maximumLengths = new int[segmentTreeLeafCount * 2];
                standaloneMaximumLengths = new int[segmentTreeLeafCount * 2];
                for (int index = 0; index < this.indexedFences.length; index++) {
                    IndexedFence indexedFence = this.indexedFences[index];
                    int leafIndex = segmentTreeLeafCount + index;
                    maximumLengths[leafIndex] = indexedFence.marker().length();
                    if (indexedFence.standalone()) {
                        standaloneMaximumLengths[leafIndex] =
                                indexedFence.marker().length();
                    }
                }
                for (int index = segmentTreeLeafCount - 1; index > 0; index--) {
                    maximumLengths[index] = Math.max(maximumLengths[index * 2], maximumLengths[index * 2 + 1]);
                    standaloneMaximumLengths[index] =
                            Math.max(standaloneMaximumLengths[index * 2], standaloneMaximumLengths[index * 2 + 1]);
                }
            }

            private Optional<IndexedFence> firstMatching(
                    int searchStartIndex, int minimumLength, boolean standaloneOnly) {
                int firstCandidateIndex = lowerBound(searchStartIndex);
                int[] searchableMaximumLengths = standaloneOnly ? standaloneMaximumLengths : maximumLengths;
                int matchingIndex = findFirst(
                        searchableMaximumLengths, 1, 0, segmentTreeLeafCount, firstCandidateIndex, minimumLength);
                return matchingIndex < indexedFences.length
                        ? Optional.of(indexedFences[matchingIndex])
                        : Optional.empty();
            }

            private int lowerBound(int searchStartIndex) {
                int lowerIndex = 0;
                int upperIndex = indexedFences.length;
                while (lowerIndex < upperIndex) {
                    int middleIndex = lowerIndex + (upperIndex - lowerIndex) / 2;
                    if (indexedFences[middleIndex].marker().startIndex() < searchStartIndex) {
                        lowerIndex = middleIndex + 1;
                    } else {
                        upperIndex = middleIndex;
                    }
                }
                return lowerIndex;
            }

            private int findFirst(
                    int[] searchableMaximumLengths,
                    int treeIndex,
                    int rangeStartIndex,
                    int rangeEndIndex,
                    int firstCandidateIndex,
                    int minimumLength) {
                if (rangeEndIndex <= firstCandidateIndex || searchableMaximumLengths[treeIndex] < minimumLength) {
                    return indexedFences.length;
                }
                if (rangeEndIndex - rangeStartIndex == 1) {
                    return rangeStartIndex;
                }
                int rangeMiddleIndex = rangeStartIndex + (rangeEndIndex - rangeStartIndex) / 2;
                int leftMatchingIndex = findFirst(
                        searchableMaximumLengths,
                        treeIndex * 2,
                        rangeStartIndex,
                        rangeMiddleIndex,
                        firstCandidateIndex,
                        minimumLength);
                if (leftMatchingIndex < indexedFences.length) {
                    return leftMatchingIndex;
                }
                return findFirst(
                        searchableMaximumLengths,
                        treeIndex * 2 + 1,
                        rangeMiddleIndex,
                        rangeEndIndex,
                        firstCandidateIndex,
                        minimumLength);
            }
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
     * Returns the marker that established the active fenced-code block.
     */
    Optional<FenceMarker> currentFenceMarker() {
        return currentFenceMarker;
    }

    /**
     * Returns whether a marker matches the active fence without requiring closing-line trivia.
     */
    boolean matchesCurrentFence(FenceMarker marker) {
        return currentFenceMarker
                .filter(activeFenceMarker -> marker.character() == activeFenceMarker.character()
                        && marker.length() >= activeFenceMarker.length())
                .isPresent();
    }

    /**
     * Builds a closing fence for a currently open code block.
     */
    String closingFence() {
        char closingCharacter = currentFenceMarker.map(FenceMarker::character).orElse(DEFAULT_FENCE_CHARACTER);
        int closingLength = currentFenceMarker.map(FenceMarker::length).orElse(FENCE_MINIMUM_LENGTH);
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

    boolean closesCurrentFence(FenceMarker marker, String markdown, int lineEndIndex) {
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
        currentFenceMarker = Optional.of(marker);
    }

    private void exitFencedCodeBlock() {
        insideFencedCodeBlock = false;
        currentFenceMarker = Optional.empty();
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

    private static boolean hasOnlySpaceOrTab(String markdown, int startIndex, int endIndex) {
        for (int cursor = startIndex; cursor < endIndex; cursor++) {
            char currentCharacter = markdown.charAt(cursor);
            if (currentCharacter != ' ' && currentCharacter != '\t') {
                return false;
            }
        }
        return true;
    }
}
