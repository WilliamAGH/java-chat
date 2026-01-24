package com.williamcallahan.javachat.service.markdown;

/**
 * Normalizes markdown text prior to AST parsing to improve list and fence handling.
 */
final class MarkdownNormalizer {
    private MarkdownNormalizer() {}

    private static final int INDENTED_CODE_BLOCK_SPACES = 4;

    // Normalize: preserve fences; convert "1) " to "1. " outside fences so Flexmark sees OLs
    static String preNormalizeForListsAndFences(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        CodeFenceStateTracker fenceTracker = new CodeFenceStateTracker();

        for (int cursor = 0; cursor < markdownText.length();) {
            boolean isStartOfLine = cursor == 0 || markdownText.charAt(cursor - 1) == '\n';
            CodeFenceStateTracker.FenceMarker fenceMarker = CodeFenceStateTracker.scanFenceMarker(markdownText, cursor);
            boolean isAttachedFenceStart = isAttachedFenceStart(markdownText, cursor);

            if (fenceMarker != null && !fenceTracker.isInsideInlineCode()) {
                if (!fenceTracker.isInsideFence() && (isStartOfLine || isAttachedFenceStart)) {
                    // Repair malformed "attached" fences like "Here:```java" by forcing the fence
                    // onto its own line, which is required for standard markdown parsing.
                    if (normalizedBuilder.length() > 0) {
                        char previousChar = normalizedBuilder.charAt(normalizedBuilder.length() - 1);
                        if (previousChar != '\n') {
                            normalizedBuilder.append('\n').append('\n');
                        } else if (normalizedBuilder.length() > 1 && normalizedBuilder.charAt(normalizedBuilder.length() - 2) != '\n') {
                            normalizedBuilder.append('\n');
                        }
                    }

                    fenceTracker.enterFence(fenceMarker.character(), fenceMarker.length());
                    appendFenceMarker(normalizedBuilder, fenceMarker, markdownText, cursor);
                    cursor += fenceMarker.length();
                    while (cursor < markdownText.length()) {
                        char languageChar = markdownText.charAt(cursor);
                        if (Character.isLetterOrDigit(languageChar) || languageChar == '-' || languageChar == '_') {
                            normalizedBuilder.append(languageChar);
                            cursor++;
                        } else {
                            break;
                        }
                    }
                    if (cursor < markdownText.length() && markdownText.charAt(cursor) != '\n') {
                        normalizedBuilder.append('\n');
                    }
                    continue;
                }

                // Only close fences when they're properly on their own line.
                if (fenceTracker.isInsideFence() && isStartOfLine && fenceTracker.wouldCloseFence(fenceMarker)) {
                    if (normalizedBuilder.length() > 0 && normalizedBuilder.charAt(normalizedBuilder.length() - 1) != '\n') {
                        normalizedBuilder.append('\n');
                    }
                    appendFenceMarker(normalizedBuilder, fenceMarker, markdownText, cursor);
                    cursor += fenceMarker.length();
                    fenceTracker.exitFence();
                    if (cursor < markdownText.length() && markdownText.charAt(cursor) != '\n') {
                        normalizedBuilder.append('\n').append('\n');
                    }
                    continue;
                }
            }
            if (!fenceTracker.isInsideFence()) {
                CodeFenceStateTracker.BacktickRun backtickRun = CodeFenceStateTracker.scanBacktickRun(markdownText, cursor);
                if (backtickRun != null) {
                    fenceTracker.processCharacter(markdownText, cursor, isStartOfLine);
                    appendBacktickRun(normalizedBuilder, markdownText, cursor, backtickRun.length());
                    cursor += backtickRun.length();
                    continue;
                }
            }
            normalizedBuilder.append(markdownText.charAt(cursor));
            cursor++;
        }
        if (fenceTracker.isInsideFence()) {
            char closingChar = fenceTracker.getFenceChar() == 0
                ? CodeFenceStateTracker.DEFAULT_FENCE_CHAR
                : fenceTracker.getFenceChar();
            int closingLength = Math.max(CodeFenceStateTracker.FENCE_MIN_LENGTH, fenceTracker.getFenceLength());
            normalizedBuilder.append('\n').append(String.valueOf(closingChar).repeat(closingLength));
        }
        // Second pass: indent blocks under numeric headers so following content
        // (bullets/enrichments/code) stays inside the same list item until next header.
        return indentBlocksUnderNumericHeaders(normalizedBuilder.toString());
    }

    private static String indentBlocksUnderNumericHeaders(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return markdownText;
        }
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        CodeFenceStateTracker fenceTracker = new CodeFenceStateTracker();
        boolean inNumericHeader = false;
        int cursor = 0;
        int textLength = markdownText.length();

        while (cursor < textLength) {
            int lineStartIndex = cursor;
            while (cursor < textLength && markdownText.charAt(cursor) != '\n') {
                cursor++;
            }
            int lineEndIndex = cursor;
            String line = markdownText.substring(lineStartIndex, lineEndIndex);
            String trimmed = line.stripLeading();

            CodeFenceStateTracker.FenceMarker marker = CodeFenceStateTracker.scanFenceMarker(trimmed, 0);
            if (marker != null) {
                if (!fenceTracker.isInsideFence()) {
                    fenceTracker.enterFence(marker.character(), marker.length());
                } else if (fenceTracker.wouldCloseFence(marker)) {
                    fenceTracker.exitFence();
                }
            }

            boolean isHeader = false;
            if (!fenceTracker.isInsideFence()) {
                int digitIndex = 0;
                while (digitIndex < trimmed.length() && Character.isDigit(trimmed.charAt(digitIndex))) {
                    digitIndex++;
                }
                if (digitIndex > 0 && digitIndex <= 3 && digitIndex < trimmed.length()) {
                    char markerChar = trimmed.charAt(digitIndex);
                    if ((markerChar == '.' || markerChar == ')')
                        && (digitIndex + 1 < trimmed.length())
                        && trimmed.charAt(digitIndex + 1) == ' ') {
                        isHeader = true;
                    }
                }
            }

            if (isHeader) {
                inNumericHeader = true;
                normalizedBuilder.append(line);
            } else if (inNumericHeader && shouldIndentContinuationLine(trimmed)) {
                normalizedBuilder.append(" ".repeat(INDENTED_CODE_BLOCK_SPACES)).append(line);
            } else {
                normalizedBuilder.append(line);
            }

            if (cursor < textLength) {
                normalizedBuilder.append('\n');
                cursor++;
            }

            if (inNumericHeader && line.isEmpty()) {
                int peekStartIndex = cursor;
                int peekEndIndex = peekStartIndex;
                while (peekEndIndex < textLength && markdownText.charAt(peekEndIndex) != '\n') {
                    peekEndIndex++;
                }
                String nextLine = markdownText.substring(peekStartIndex, peekEndIndex);
                if (nextLine.isEmpty()) {
                    inNumericHeader = false;
                }
            }
        }
        return normalizedBuilder.toString();
    }

    private static boolean shouldIndentContinuationLine(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) {
            return false;
        }
        if (CodeFenceStateTracker.scanFenceMarker(trimmedLine, 0) != null) {
            return false;
        }
        char firstChar = trimmedLine.charAt(0);
        boolean unorderedMarker = firstChar == '-' || firstChar == '*' || firstChar == '+' || firstChar == '•';
        return !unorderedMarker && !startsWithOrderedMarker(trimmedLine);
    }

    private static void appendFenceMarker(StringBuilder builder, CodeFenceStateTracker.FenceMarker marker,
                                          String text, int index) {
        for (int offset = 0; offset < marker.length(); offset++) {
            builder.append(text.charAt(index + offset));
        }
    }

    private static void appendBacktickRun(StringBuilder builder, String text, int index, int length) {
        for (int offset = 0; offset < length; offset++) {
            builder.append(text.charAt(index + offset));
        }
    }

    private static boolean isAttachedFenceStart(String text, int index) {
        if (text == null || index <= 0 || index > text.length() - 1) {
            return false;
        }
        char previousChar = text.charAt(index - 1);
        return !Character.isWhitespace(previousChar);
    }

    private static boolean startsWithOrderedMarker(String trimmedLine) {
        int cursor = 0;
        while (cursor < trimmedLine.length() && Character.isDigit(trimmedLine.charAt(cursor))) {
            cursor++;
        }
        if (cursor > 0 && cursor < trimmedLine.length()) {
            char marker = trimmedLine.charAt(cursor);
            if ((marker == '.' || marker == ')') && cursor + 1 < trimmedLine.length()) {
                return true;
            }
        }
        // Lowercase letter marker: a. / a)
        if (trimmedLine.length() > 1) {
            char firstChar = trimmedLine.charAt(0);
            char secondChar = trimmedLine.charAt(1);
            if (firstChar >= 'a' && firstChar <= 'z' && (secondChar == '.' || secondChar == ')')) {
                return true;
            }
        }
        // Basic lowercase roman markers (i., ii., iii., iv., v.)
        int romanCursor = 0;
        while (romanCursor < trimmedLine.length() && romanCursor < 6) {
            char romanChar = trimmedLine.charAt(romanCursor);
            if (romanChar != 'i' && romanChar != 'v' && romanChar != 'x') {
                break;
            }
            romanCursor++;
        }
        if (romanCursor > 0 && romanCursor < trimmedLine.length()) {
            char marker = trimmedLine.charAt(romanCursor);
            return marker == '.' || marker == ')';
        }
        return false;
    }
}
