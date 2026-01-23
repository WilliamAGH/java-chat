package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.util.ast.Node;

final class MarkdownAstUtils {
    private MarkdownAstUtils() {}

    static void stripInlineCitationMarkers(Node root) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNext()) {
            // Skip code blocks/spans and links entirely
            if (n instanceof com.vladsch.flexmark.ast.Code) continue;
            if (n instanceof com.vladsch.flexmark.ast.FencedCodeBlock) continue;
            if (n instanceof com.vladsch.flexmark.ast.Link) { stripInlineCitationMarkers(n); continue; }
            if (n instanceof com.vladsch.flexmark.ast.Text t) {
                CharSequence cs = t.getChars();
                String s = cs.toString();
                String cleaned = removeBracketNumbers(s);
                if (!cleaned.equals(s)) {
                    t.setChars(com.vladsch.flexmark.util.sequence.BasedSequence.of(cleaned));
                }
            }
            if (n.hasChildren()) stripInlineCitationMarkers(n);
        }
    }

    private static String removeBracketNumbers(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '[') {
                int j = i + 1; int digits = 0; boolean valid = true;
                while (j < s.length() && Character.isDigit(s.charAt(j)) && digits < 3) { j++; digits++; }
                if (digits == 0) valid = false;
                if (valid && j < s.length() && s.charAt(j) == ']') {
                    if (isBracketNumber(s, i, j)) {
                        i = skipSpacesAfterBracket(s, j + 1, out);
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int skipSpacesAfterBracket(String s, int nextI, StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            while (nextI < s.length() && s.charAt(nextI) == ' ') nextI++;
        }
        return nextI;
    }

    private static boolean isBracketNumber(String s, int i, int j) {
        if (i > 0) {
            char prev = s.charAt(i - 1);
            if (Character.isLetterOrDigit(prev)) return false;
        }
        if (j + 1 < s.length()) {
            char next = s.charAt(j + 1);
            if (Character.isLetterOrDigit(next)) return false;
        }
        return true;
    }
}
