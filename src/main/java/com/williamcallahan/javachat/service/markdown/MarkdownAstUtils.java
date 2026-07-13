package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.util.ast.Node;

/**
 * AST utilities for markdown cleanup and normalization.
 */
final class MarkdownAstUtils {
    private MarkdownAstUtils() {}

    private static final int MAX_CITATION_MARKER_DIGITS = 3;

    static void stripInlineCitationMarkers(Node root) {
        for (Node markdownNode = root.getFirstChild(); markdownNode != null; ) {
            Node nextMarkdownNode = markdownNode.getNext();
            // Skip code blocks/spans and links entirely
            if (markdownNode instanceof com.vladsch.flexmark.ast.Code
                    || markdownNode instanceof com.vladsch.flexmark.ast.FencedCodeBlock
                    || markdownNode instanceof com.vladsch.flexmark.ast.IndentedCodeBlock
                    || markdownNode instanceof com.vladsch.flexmark.ast.Link) {
                markdownNode = nextMarkdownNode;
                continue;
            }
            if (markdownNode instanceof LinkRef linkReference) {
                if (isUnresolvedCitationReference(linkReference)) {
                    linkReference.unlink();
                }
                markdownNode = nextMarkdownNode;
                continue;
            }
            if (markdownNode instanceof com.vladsch.flexmark.ast.Text textNode) {
                CharSequence nodeChars = textNode.getChars();
                String rawText = nodeChars.toString();
                String cleanedText = removeBracketNumbers(rawText);
                if (!cleanedText.equals(rawText)) {
                    textNode.setChars(com.vladsch.flexmark.util.sequence.BasedSequence.of(cleanedText));
                }
            }
            if (markdownNode.hasChildren()) {
                stripInlineCitationMarkers(markdownNode);
            }
            markdownNode = nextMarkdownNode;
        }
    }

    private static boolean isUnresolvedCitationReference(LinkRef linkReference) {
        return !linkReference.isDefined()
                && removeBracketNumbers(linkReference.getChars().toString()).isEmpty();
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
                while (scanIndex < text.length()
                        && Character.isDigit(text.charAt(scanIndex))
                        && digitCount < MAX_CITATION_MARKER_DIGITS) {
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
