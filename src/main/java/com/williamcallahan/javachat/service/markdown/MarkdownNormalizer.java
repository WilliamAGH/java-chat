package com.williamcallahan.javachat.service.markdown;

import java.util.Optional;

/**
 * Normalizes Markdown text before AST parsing while preserving code block boundaries.
 */
final class MarkdownNormalizer {
    private MarkdownNormalizer() {}

    private static final int LIST_CONTINUATION_INDENTATION_SPACES = 4;

    /**
     * Repairs attached fences and keeps content following numeric headers in the same list item.
     *
     * @param markdownText Markdown supplied by a caller
     * @return Markdown with parser-safe fence and list structure
     */
    static String preNormalizeForListsAndFences(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }
        return indentBlocksUnderNumericHeaders(normalizeFences(markdownText));
    }

    private static String normalizeFences(String markdownText) {
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        MarkdownBlockContext blockContext = new MarkdownBlockContext();
        MarkdownBlockContext.FenceIndex fenceIndex = new MarkdownBlockContext.FenceIndex(
                markdownText,
                (sourceMarkdown, indexedFence) -> indexedFence.standalone()
                        || hasTrailingProse(
                                sourceMarkdown, indexedFence.marker().endIndex(), indexedFence.lineEndIndex()));
        boolean attachedFenceOpen = false;
        int lineStartIndex = 0;

        while (lineStartIndex < markdownText.length()) {
            int lineEndIndex = MarkdownBlockContext.lineEndIndex(markdownText, lineStartIndex);
            boolean startedInsideFence = blockContext.isInsideFencedCodeBlock();
            if (attachedFenceOpen
                    && appendAttachedClosingFenceWithProse(
                            normalizedBuilder, markdownText, lineStartIndex, lineEndIndex, blockContext, fenceIndex)) {
                attachedFenceOpen = false;
            } else {
                MarkdownBlockContext.LineContext lineContext =
                        blockContext.classifyLine(markdownText, lineStartIndex, lineEndIndex);
                if (lineContext.isCodeBlock()) {
                    normalizedBuilder.append(markdownText, lineStartIndex, lineEndIndex);
                } else if (appendTextLine(
                        normalizedBuilder, markdownText, lineStartIndex, lineEndIndex, blockContext, fenceIndex)) {
                    attachedFenceOpen = true;
                }
                if (startedInsideFence && !blockContext.isInsideFencedCodeBlock()) {
                    attachedFenceOpen = false;
                }
            }

            if (lineEndIndex < markdownText.length()) {
                normalizedBuilder.append('\n');
            }
            lineStartIndex = lineEndIndex + 1;
        }

        if (blockContext.isInsideFencedCodeBlock()) {
            appendLineBreakIfNeeded(normalizedBuilder);
            normalizedBuilder.append(blockContext.closingFence());
        }
        return normalizedBuilder.toString();
    }

    private static boolean appendAttachedClosingFenceWithProse(
            StringBuilder normalizedBuilder,
            String markdownText,
            int lineStartIndex,
            int lineEndIndex,
            MarkdownBlockContext blockContext,
            MarkdownBlockContext.FenceIndex fenceIndex) {
        Optional<MarkdownBlockContext.FenceMarker> fenceMarker =
                MarkdownBlockContext.scanFenceAtBlockIndentation(markdownText, lineStartIndex, lineEndIndex);
        if (fenceMarker.isEmpty() || !blockContext.matchesCurrentFence(fenceMarker.get())) {
            return false;
        }
        int markerEndIndex = fenceMarker.get().endIndex();
        if (!hasTrailingProse(markdownText, markerEndIndex, lineEndIndex)) {
            return false;
        }
        if (blockContext
                .currentFenceMarker()
                .flatMap(activeFence -> fenceIndex.firstMatching(lineEndIndex + 1, activeFence, true))
                .isPresent()) {
            return false;
        }

        normalizedBuilder.append(markdownText, lineStartIndex, markerEndIndex);
        blockContext.classifyLine(markdownText, lineStartIndex, markerEndIndex);
        appendLineBreakIfNeeded(normalizedBuilder);
        appendTextLine(normalizedBuilder, markdownText, markerEndIndex, lineEndIndex, blockContext, fenceIndex);
        return true;
    }

    private static boolean hasTrailingProse(String markdownText, int suffixStartIndex, int lineEndIndex) {
        int firstVisibleIndex = suffixStartIndex;
        while (firstVisibleIndex < lineEndIndex && Character.isWhitespace(markdownText.charAt(firstVisibleIndex))) {
            firstVisibleIndex++;
        }
        if (firstVisibleIndex >= lineEndIndex) {
            return false;
        }
        if (isBalancedParentheticalProse(markdownText, firstVisibleIndex, lineEndIndex)) {
            return true;
        }
        char firstVisibleCharacter = markdownText.charAt(firstVisibleIndex);
        if (!Character.isLetterOrDigit(firstVisibleCharacter)) {
            return false;
        }
        for (int cursor = firstVisibleIndex + 1; cursor < lineEndIndex; cursor++) {
            if (Character.isWhitespace(markdownText.charAt(cursor))) {
                return true;
            }
        }
        // A capitalized lone word is the compact-prose repair case only when this candidate
        // terminates an attached fence. A real opening fence still owns its info string, while
        // appendAttachedClosingFenceWithProse preserves this line as literal code whenever a
        // later structural closing fence proves that the active block continues.
        return Character.isUpperCase(firstVisibleCharacter);
    }

    private static boolean isBalancedParentheticalProse(String markdownText, int firstVisibleIndex, int lineEndIndex) {
        int lastVisibleIndex = lineEndIndex - 1;
        while (lastVisibleIndex > firstVisibleIndex && Character.isWhitespace(markdownText.charAt(lastVisibleIndex))) {
            lastVisibleIndex--;
        }
        if (markdownText.charAt(firstVisibleIndex) != '(' || markdownText.charAt(lastVisibleIndex) != ')') {
            return false;
        }
        int parenthesisDepth = 0;
        boolean containsVisibleText = false;
        for (int cursor = firstVisibleIndex; cursor <= lastVisibleIndex; cursor++) {
            char currentCharacter = markdownText.charAt(cursor);
            if (currentCharacter == '(') {
                parenthesisDepth++;
            } else if (currentCharacter == ')') {
                parenthesisDepth--;
                if (parenthesisDepth < 0) {
                    return false;
                }
            } else if (Character.isLetterOrDigit(currentCharacter)) {
                containsVisibleText = true;
            }
        }
        return parenthesisDepth == 0 && containsVisibleText;
    }

    private static boolean appendTextLine(
            StringBuilder normalizedBuilder,
            String markdownText,
            int lineStartIndex,
            int lineEndIndex,
            MarkdownBlockContext blockContext,
            MarkdownBlockContext.FenceIndex fenceIndex) {
        int cursor = lineStartIndex;
        while (cursor < lineEndIndex) {
            Optional<MarkdownBlockContext.FenceMarker> attachedFenceMarker =
                    MarkdownBlockContext.scanFenceMarker(markdownText, cursor, lineEndIndex);
            if (!blockContext.isInsideInlineCode()
                    && attachedFenceMarker.isPresent()
                    && cursor > lineStartIndex
                    && !Character.isWhitespace(markdownText.charAt(cursor - 1))) {
                MarkdownBlockContext.FenceMarker openingFenceMarker = attachedFenceMarker.get();
                boolean preferFence = openingFenceMarker.usesTilde()
                        || hasLaterStructuralClosingFence(lineEndIndex + 1, openingFenceMarker, fenceIndex);
                if (!preferFence) {
                    int inlineDelimiterLength = blockContext.consumeInlineCodeDelimiter(markdownText, cursor);
                    if (inlineDelimiterLength > 0) {
                        normalizedBuilder.append(markdownText, cursor, cursor + inlineDelimiterLength);
                        cursor += inlineDelimiterLength;
                        continue;
                    }
                }
                MarkdownBlockContext.LineContext attachedFenceContext =
                        blockContext.classifyLine(markdownText, cursor, lineEndIndex);
                if (attachedFenceContext.isCodeBlock()) {
                    appendLineBreakIfNeeded(normalizedBuilder);
                    normalizedBuilder.append(markdownText, cursor, lineEndIndex);
                    return true;
                }
            }

            int inlineDelimiterLength = blockContext.consumeInlineCodeDelimiter(markdownText, cursor);
            if (inlineDelimiterLength > 0) {
                normalizedBuilder.append(markdownText, cursor, cursor + inlineDelimiterLength);
                cursor += inlineDelimiterLength;
                continue;
            }

            normalizedBuilder.append(markdownText.charAt(cursor));
            cursor++;
        }
        return false;
    }

    private static boolean hasLaterStructuralClosingFence(
            int searchStartIndex,
            MarkdownBlockContext.FenceMarker openingFenceMarker,
            MarkdownBlockContext.FenceIndex fenceIndex) {
        return fenceIndex
                .firstMatching(searchStartIndex, openingFenceMarker, false)
                .isPresent();
    }

    private static String indentBlocksUnderNumericHeaders(String markdownText) {
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        MarkdownBlockContext blockContext = new MarkdownBlockContext();
        boolean inNumericHeader = false;
        int lineStartIndex = 0;

        while (lineStartIndex < markdownText.length()) {
            int lineEndIndex = MarkdownBlockContext.lineEndIndex(markdownText, lineStartIndex);
            String line = markdownText.substring(lineStartIndex, lineEndIndex);
            String trimmedLine = line.stripLeading();
            boolean startedInsideInlineCode = blockContext.isInsideInlineCode();
            MarkdownBlockContext.LineContext lineContext =
                    blockContext.classifyLine(markdownText, lineStartIndex, lineEndIndex);
            boolean isNumericHeader = lineContext == MarkdownBlockContext.LineContext.TEXT
                    && !startedInsideInlineCode
                    && isNumericHeader(trimmedLine);

            if (isNumericHeader) {
                inNumericHeader = true;
                normalizedBuilder.append(line);
            } else if (inNumericHeader && (lineContext.isCodeBlock() || shouldIndentContinuationLine(trimmedLine))) {
                normalizedBuilder
                        .append(" ".repeat(LIST_CONTINUATION_INDENTATION_SPACES))
                        .append(line);
            } else {
                normalizedBuilder.append(line);
            }

            if (lineContext == MarkdownBlockContext.LineContext.TEXT) {
                blockContext.consumeInlineCodeDelimiters(markdownText, lineStartIndex, lineEndIndex);
            }

            if (lineEndIndex < markdownText.length()) {
                normalizedBuilder.append('\n');
            }

            int nextLineStartIndex = lineEndIndex + 1;
            if (inNumericHeader && line.isEmpty()) {
                int nextLineEndIndex = MarkdownBlockContext.lineEndIndex(markdownText, nextLineStartIndex);
                if (nextLineStartIndex >= markdownText.length() || nextLineEndIndex == nextLineStartIndex) {
                    inNumericHeader = false;
                }
            }
            lineStartIndex = nextLineStartIndex;
        }
        return normalizedBuilder.toString();
    }

    private static boolean shouldIndentContinuationLine(String trimmedLine) {
        if (trimmedLine.isEmpty()) {
            return false;
        }
        char firstCharacter = trimmedLine.charAt(0);
        boolean unorderedMarker =
                firstCharacter == '-' || firstCharacter == '*' || firstCharacter == '+' || firstCharacter == '•';
        return !unorderedMarker && !OrderedMarkerScanner.startsWithOrderedMarker(trimmedLine);
    }

    private static boolean isNumericHeader(String trimmedLine) {
        return OrderedMarkerScanner.startsWithNumericOrderedMarker(trimmedLine);
    }

    private static void appendLineBreakIfNeeded(StringBuilder builder) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
    }
}
