package com.williamcallahan.javachat.service.markdown;

final class MarkdownNormalizer {
    private MarkdownNormalizer() {}

    // Normalize: preserve fences; convert "1) " to "1. " outside fences so Flexmark sees OLs
    static String preNormalizeForListsAndFences(String md) {
        if (md == null || md.isEmpty()) return "";
        StringBuilder out = new StringBuilder(md.length() + 64);
        boolean inFence = false;
        for (int i = 0; i < md.length();) {
            if (i + 2 < md.length() && md.charAt(i) == '`' && md.charAt(i + 1) == '`' && md.charAt(i + 2) == '`') {
                boolean opening = !inFence;
                if (opening && out.length() > 0) {
                    char prev = out.charAt(out.length() - 1);
                    if (prev != '\n') out.append('\n').append('\n');
                }
                out.append("```");
                i += 3;
                while (i < md.length()) {
                    char ch = md.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') { out.append(ch); i++; }
                    else break;
                }
                if (i < md.length() && md.charAt(i) != '\n') { out.append('\n'); }
                inFence = true;
                continue;
            }
            if (inFence && i + 2 < md.length() && md.charAt(i) == '`' && md.charAt(i + 1) == '`' && md.charAt(i + 2) == '`') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') { out.append('\n'); }
                out.append("```");
                i += 3;
                inFence = false;
                if (i < md.length() && md.charAt(i) != '\n') out.append('\n').append('\n');
                continue;
            }
            out.append(md.charAt(i));
            i++;
        }
        if (inFence) { out.append('\n').append("```"); }
        // Second pass: indent blocks under numeric headers so following content
        // (bullets/enrichments/code) stays inside the same list item until next header.
        return indentBlocksUnderNumericHeaders(out.toString());
    }

    private static String indentBlocksUnderNumericHeaders(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + 64);
        boolean inFence = false;
        boolean inNumericHeader = false;
        int i = 0; int n = text.length();
        while (i < n) {
            int lineStart = i;
            while (i < n && text.charAt(i) != '\n') i++;
            int lineEnd = i; // exclusive
            String line = text.substring(lineStart, lineEnd);
            String trimmed = line.stripLeading();
            // fence toggle
            if (trimmed.startsWith("```") && !trimmed.startsWith("````")) {
                inFence = !inFence;
            }
            boolean isHeader = false;
            if (!inFence) {
                int j = 0;
                while (j < trimmed.length() && Character.isDigit(trimmed.charAt(j))) j++;
                if (j > 0 && j <= 3 && j < trimmed.length()) {
                    char c = trimmed.charAt(j);
                    if ((c == '.' || c == ')') && (j + 1 < trimmed.length()) && trimmed.charAt(j + 1) == ' ') {
                        isHeader = true;
                    }
                }
            }
            if (isHeader) {
                inNumericHeader = true;
                out.append(line);
            } else if (inNumericHeader) {
                // indent non-header lines under the current numbered header
                if (line.isEmpty()) {
                    out.append("    ");
                    out.append(line);
                } else {
                    // keep existing leading spaces but ensure at least 4
                    out.append("    ");
                    out.append(line);
                }
            } else {
                out.append(line);
            }
            if (i < n) { out.append('\n'); i++; }
            // Stop header scope if we hit two consecutive blank lines (common section break)
            if (inNumericHeader && line.isEmpty()) {
                // peek next line
                int k = i; int m = k;
                while (m < n && text.charAt(m) != '\n') m++;
                String nextLine = text.substring(k, m);
                if (nextLine.isEmpty()) inNumericHeader = false;
            }
        }
        return out.toString();
    }
}
