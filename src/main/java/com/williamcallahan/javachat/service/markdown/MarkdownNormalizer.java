package com.williamcallahan.javachat.service.markdown;

/**
 * Normalizes markdown text prior to AST parsing to improve list and fence handling.
 */
final class MarkdownNormalizer {
    private MarkdownNormalizer() {}

    // Normalize: preserve fences; convert "1) " to "1. " outside fences so Flexmark sees OLs
    static String preNormalizeForListsAndFences(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }
        StringBuilder normalizedBuilder = new StringBuilder(markdownText.length() + 64);
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        for (int cursor = 0; cursor < markdownText.length();) {
            boolean isStartOfLine = cursor == 0 || markdownText.charAt(cursor - 1) == '\n';
            FenceMarker marker = scanFenceMarker(markdownText, cursor);
            if (marker != null) {
                if (!inFence) {
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
                    fenceChar = marker.character();
                    fenceLength = marker.length();
                    appendFenceMarker(normalizedBuilder, marker, markdownText, cursor);
                    cursor += marker.length();
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
                if (isStartOfLine && marker.character() == fenceChar && marker.length() >= fenceLength) {
                    if (normalizedBuilder.length() > 0 && normalizedBuilder.charAt(normalizedBuilder.length() - 1) != '\n') {
                        normalizedBuilder.append('\n');
                    }
                    appendFenceMarker(normalizedBuilder, marker, markdownText, cursor);
                    cursor += marker.length();
                    inFence = false;
                    fenceChar = 0;
                    fenceLength = 0;
                    if (cursor < markdownText.length() && markdownText.charAt(cursor) != '\n') {
                        normalizedBuilder.append('\n').append('\n');
                    }
                    continue;
                }
            }
            normalizedBuilder.append(markdownText.charAt(cursor));
            cursor++;
        }
        if (inFence) {
            char closingChar = fenceChar == 0 ? '`' : fenceChar;
            int closingLength = Math.max(3, fenceLength);
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
                normalizedBuilder.append("    ").append(line);
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

    private static FenceMarker scanFenceMarker(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        char markerChar = text.charAt(index);
        if (markerChar != '`' && markerChar != '~') {
            return null;
        }
        int length = 0;
        while (index + length < text.length() && text.charAt(index + length) == markerChar) {
            length++;
        }
        return length >= 3 ? new FenceMarker(markerChar, length) : null;
    }

    private static void appendFenceMarker(StringBuilder builder, FenceMarker marker, String text, int index) {
        for (int offset = 0; offset < marker.length(); offset++) {
            builder.append(text.charAt(index + offset));
        }
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
