package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.util.ast.Node;

/**
 * AST utilities for markdown cleanup and normalization.
 */
final class MarkdownAstUtils {
    private MarkdownAstUtils() {}

    static void stripInlineCitationMarkers(Node root) {
        for (Node childNode = root.getFirstChild(); childNode != null; childNode = childNode.getNext()) {
            // Skip code blocks/spans and links entirely
            if (childNode instanceof com.vladsch.flexmark.ast.Code) continue;
            if (childNode instanceof com.vladsch.flexmark.ast.FencedCodeBlock) continue;
            if (childNode instanceof com.vladsch.flexmark.ast.IndentedCodeBlock) continue;
            if (childNode instanceof com.vladsch.flexmark.ast.Link) {
                stripInlineCitationMarkers(childNode);
                continue;
            }
            if (childNode instanceof com.vladsch.flexmark.ast.Text textNode) {
                CharSequence nodeChars = textNode.getChars();
                String rawText = nodeChars.toString();
                String cleanedText = removeBracketNumbers(rawText);
                if (!cleanedText.equals(rawText)) {
                    textNode.setChars(com.vladsch.flexmark.util.sequence.BasedSequence.of(cleanedText));
                }
            }
            if (childNode.hasChildren()) {
                stripInlineCitationMarkers(childNode);
            }
        }
    }

    private static String removeBracketNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder cleanedBuilder = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            char currentChar = text.charAt(cursor);
            if (currentChar == '[') {
                int scanIndex = cursor + 1;
                int digitCount = 0;
                boolean validToken = true;
                while (scanIndex < text.length() && Character.isDigit(text.charAt(scanIndex)) && digitCount < 3) {
                    scanIndex++;
                    digitCount++;
                }
                if (digitCount == 0) {
                    validToken = false;
                }
                if (validToken && scanIndex < text.length() && text.charAt(scanIndex) == ']') {
                    if (isBracketNumber(text, cursor, scanIndex)) {
                        cursor = skipSpacesAfterBracket(text, scanIndex + 1, cleanedBuilder);
                        continue;
                    }
                }
            }
            cleanedBuilder.append(currentChar);
            cursor++;
        }
        return cleanedBuilder.toString();
    }

    private static int skipSpacesAfterBracket(String text, int nextIndex, StringBuilder cleanedBuilder) {
        if (cleanedBuilder.length() > 0 && cleanedBuilder.charAt(cleanedBuilder.length() - 1) == ' ') {
            while (nextIndex < text.length() && text.charAt(nextIndex) == ' ') {
                nextIndex++;
            }
        }
        return nextIndex;
    }

    private static boolean isBracketNumber(String text, int openIndex, int closeIndex) {
        if (openIndex > 0) {
            char previousChar = text.charAt(openIndex - 1);
            if (Character.isLetterOrDigit(previousChar)) {
                return false;
            }
        }
        if (closeIndex + 1 < text.length()) {
            char nextChar = text.charAt(closeIndex + 1);
            if (Character.isLetterOrDigit(nextChar)) {
                return false;
            }
        }
        return true;
    }
}
