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
        int lineStartIndex = 0;

        while (lineStartIndex < markdownText.length()) {
            int lineEndIndex = MarkdownBlockContext.lineEndIndex(markdownText, lineStartIndex);
            if (!appendDetachedClosingFenceIfPresent(
                    normalizedBuilder, markdownText, lineStartIndex, lineEndIndex, blockContext)) {
                MarkdownBlockContext.LineContext lineContext =
                        blockContext.classifyLine(markdownText, lineStartIndex, lineEndIndex);
                if (lineContext.isCodeBlock()) {
                    normalizedBuilder.append(markdownText, lineStartIndex, lineEndIndex);
                } else {
                    appendTextLine(normalizedBuilder, markdownText, lineStartIndex, lineEndIndex, blockContext);
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

    private static boolean appendDetachedClosingFenceIfPresent(
            StringBuilder normalizedBuilder,
            String markdownText,
            int lineStartIndex,
            int lineEndIndex,
            MarkdownBlockContext blockContext) {
        if (!blockContext.isInsideFencedCodeBlock()) {
            return false;
        }

        Optional<MarkdownBlockContext.FenceMarker> fenceMarker =
                MarkdownBlockContext.scanFenceAtBlockIndentation(markdownText, lineStartIndex, lineEndIndex);
        if (fenceMarker.isEmpty() || !blockContext.matchesCurrentFence(fenceMarker.get())) {
            return false;
        }

        int markerEndIndex = fenceMarker.get().endIndex();
        if (hasOnlySpaceOrTab(markdownText, markerEndIndex, lineEndIndex)) {
            return false;
        }

        normalizedBuilder.append(markdownText, lineStartIndex, markerEndIndex);
        blockContext.classifyLine(markdownText, lineStartIndex, markerEndIndex);
        appendLineBreakIfNeeded(normalizedBuilder);
        appendTextLine(normalizedBuilder, markdownText, markerEndIndex, lineEndIndex, blockContext);
        return true;
    }

    private static void appendTextLine(
            StringBuilder normalizedBuilder,
            String markdownText,
            int lineStartIndex,
            int lineEndIndex,
            MarkdownBlockContext blockContext) {
        int cursor = lineStartIndex;
        while (cursor < lineEndIndex) {
            Optional<MarkdownBlockContext.FenceMarker> attachedFenceMarker =
                    MarkdownBlockContext.scanFenceMarker(markdownText, cursor, lineEndIndex);
            if (!blockContext.isInsideInlineCode()
                    && attachedFenceMarker.isPresent()
                    && cursor > lineStartIndex
                    && !Character.isWhitespace(markdownText.charAt(cursor - 1))) {
                MarkdownBlockContext.LineContext attachedFenceContext =
                        blockContext.classifyLine(markdownText, cursor, lineEndIndex);
                if (attachedFenceContext.isCodeBlock()) {
                    appendLineBreakIfNeeded(normalizedBuilder);
                    normalizedBuilder.append(markdownText, cursor, lineEndIndex);
                    return;
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
            } else if (inNumericHeader && startsEnrichmentMarker(trimmedLine)) {
                inNumericHeader = false;
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

    private static boolean startsEnrichmentMarker(String trimmedLine) {
        return trimmedLine.startsWith("{{");
    }

    private static boolean isNumericHeader(String trimmedLine) {
        return OrderedMarkerScanner.startsWithNumericOrderedMarker(trimmedLine);
    }

    private static void appendLineBreakIfNeeded(StringBuilder builder) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
    }

    private static boolean hasOnlySpaceOrTab(String markdownText, int startIndex, int endIndex) {
        for (int cursor = startIndex; cursor < endIndex; cursor++) {
            char currentCharacter = markdownText.charAt(cursor);
            if (currentCharacter != ' ' && currentCharacter != '\t') {
                return false;
            }
        }
        return true;
    }
}
