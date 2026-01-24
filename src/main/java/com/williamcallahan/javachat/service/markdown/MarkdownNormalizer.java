package com.williamcallahan.javachat.service.markdown;

/**
 * Normalizes markdown text prior to AST parsing to improve list and fence handling.
 */
final class MarkdownNormalizer {
    private MarkdownNormalizer() {}

    private static final int FENCE_MIN_LENGTH = 3;
    private static final int INDENTED_CODE_BLOCK_SPACES = 4;
    private static final char BACKTICK = '`';
    private static final char TILDE = '~';

    // Normalize: preserve fences; convert "1) " to "1. " outside fences so Flexmark sees OLs
    static String preNormalizeForListsAndFences(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        boolean inInlineCode = false;
        int inlineBacktickLength = 0;
        for (int cursor = 0; cursor < markdownText.length();) {
            boolean isStartOfLine = cursor == 0 || markdownText.charAt(cursor - 1) == '\n';
            FenceMarker fenceMarker = scanFenceMarker(markdownText, cursor);
            boolean isAttachedFenceStart = isAttachedFenceStart(markdownText, cursor);
            if (fenceMarker != null && !inInlineCode) {
                if (!inFence && (isStartOfLine || isAttachedFenceStart)) {
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

                    inFence = true;
                    fenceChar = fenceMarker.character();
                    fenceLength = fenceMarker.length();
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
                if (inFence && isStartOfLine && fenceMarker.character() == fenceChar && fenceMarker.length() >= fenceLength) {
                    if (normalizedBuilder.length() > 0 && normalizedBuilder.charAt(normalizedBuilder.length() - 1) != '\n') {
                        normalizedBuilder.append('\n');
                    }
                    appendFenceMarker(normalizedBuilder, fenceMarker, markdownText, cursor);
                    cursor += fenceMarker.length();
                    inFence = false;
                    fenceChar = 0;
                    fenceLength = 0;
                    if (cursor < markdownText.length() && markdownText.charAt(cursor) != '\n') {
                        normalizedBuilder.append('\n').append('\n');
                    }
                    continue;
                }
            }
            if (!inFence) {
                BacktickRun backtickRun = scanBacktickRun(markdownText, cursor);
                if (backtickRun != null) {
                    if (!inInlineCode && !hasClosingBacktickRun(markdownText, cursor, backtickRun.length())) {
                        appendBacktickRun(normalizedBuilder, markdownText, cursor, backtickRun.length());
                        cursor += backtickRun.length();
                        continue;
                    }
                    if (!inInlineCode) {
                        inInlineCode = true;
                        inlineBacktickLength = backtickRun.length();
                    } else if (backtickRun.length() == inlineBacktickLength) {
                        inInlineCode = false;
                        inlineBacktickLength = 0;
                    }
                    appendBacktickRun(normalizedBuilder, markdownText, cursor, backtickRun.length());
                    cursor += backtickRun.length();
                    continue;
                }
            }
            normalizedBuilder.append(markdownText.charAt(cursor));
            cursor++;
        }
        if (inFence) {
            char closingChar = fenceChar == 0 ? BACKTICK : fenceChar;
            int closingLength = Math.max(FENCE_MIN_LENGTH, fenceLength);
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
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        boolean inNumericHeader = false;
        int cursor = 0;
        int textLength = markdownText.length();
        while (cursor < textLength) {
            int lineStartIndex = cursor;
            while (cursor < textLength && markdownText.charAt(cursor) != '\n') {
                cursor++;
            }
            int lineEndIndex = cursor; // exclusive
            String line = markdownText.substring(lineStartIndex, lineEndIndex);
            String trimmed = line.stripLeading();
            // fence toggle
            FenceMarker marker = scanFenceMarker(trimmed, 0);
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
            }
            boolean isHeader = false;
            if (!inFence) {
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
                // Indent only true continuation prose under a numbered header.
                // Avoid indenting list markers (would turn nested lists into code blocks).
                normalizedBuilder.append(" ".repeat(INDENTED_CODE_BLOCK_SPACES)).append(line);
            } else {
                normalizedBuilder.append(line);
            }
            if (cursor < textLength) {
                normalizedBuilder.append('\n');
                cursor++;
            }
            // Stop header scope if we hit two consecutive blank lines (common section break)
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
        // Keep nested lists and fences as-is; they already carry their own structure.
        if (scanFenceMarker(trimmedLine, 0) != null) {
            return false;
        }
        char firstChar = trimmedLine.charAt(0);
        boolean unorderedMarker = firstChar == '-' || firstChar == '*' || firstChar == '+' || firstChar == '•';
        // Skip indentation for ordered list markers (1. / 1) / a. / i.)
        return !unorderedMarker && !startsWithOrderedMarker(trimmedLine);
    }

    private record FenceMarker(char character, int length) {}
    private record BacktickRun(int length) {}

    private static FenceMarker scanFenceMarker(String text, int index) {
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

    private static void appendFenceMarker(StringBuilder builder, FenceMarker marker, String text, int index) {
        for (int offset = 0; offset < marker.length(); offset++) {
            builder.append(text.charAt(index + offset));
        }
    }

    private static BacktickRun scanBacktickRun(String text, int index) {
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

    private static void appendBacktickRun(StringBuilder builder, String text, int index, int length) {
        for (int offset = 0; offset < length; offset++) {
            builder.append(text.charAt(index + offset));
        }
    }

    private static boolean hasClosingBacktickRun(String text, int startIndex, int runLength) {
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
