package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class EnrichmentPlaceholderizer {
    private final Parser parser;
    private final HtmlRenderer renderer;

    EnrichmentPlaceholderizer(Parser parser, HtmlRenderer renderer) {
        this.parser = parser;
        this.renderer = renderer;
    }

    String extractAndPlaceholderizeEnrichments(String markdown, List<MarkdownEnrichment> enrichments, Map<String, String> placeholders) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        StringBuilder result = new StringBuilder(markdown.length() + 64);
        int i = 0;
        boolean inFence = false;
        int absolutePosition = 0; // running position for enrichment creation

        while (i < markdown.length()) {
            // Toggle code fence state and copy fence blocks verbatim
            if (i + 2 < markdown.length() && markdown.charAt(i) == '`' && markdown.charAt(i + 1) == '`' && markdown.charAt(i + 2) == '`') {
                inFence = !inFence;
                result.append("```");
                i += 3;
                // Copy optional language token and the rest of the line
                while (i < markdown.length()) {
                    char c = markdown.charAt(i);
                    result.append(c);
                    i++;
                    if (c == '\n') break;
                }
                continue;
            }

            // Detect enrichment start only when not inside code fences
            if (!inFence && i + 1 < markdown.length() && markdown.charAt(i) == '{' && markdown.charAt(i + 1) == '{') {
                int tStart = i + 2;
                // skip spaces
                while (tStart < markdown.length() && Character.isWhitespace(markdown.charAt(tStart))) tStart++;
                // read type token
                int tEnd = tStart;
                while (tEnd < markdown.length() && Character.isLetter(markdown.charAt(tEnd))) tEnd++;
                String type = markdown.substring(tStart, Math.min(tEnd, markdown.length())).toLowerCase();
                // skip spaces
                int p = tEnd;
                while (p < markdown.length() && Character.isWhitespace(markdown.charAt(p))) p++;
                boolean hasColon = (p < markdown.length() && markdown.charAt(p) == ':');
                if (hasColon && isKnownEnrichmentType(type)) {
                    int contentStart = p + 1;
                    if (contentStart < markdown.length() && markdown.charAt(contentStart) == ' ') contentStart++;
                    EnrichmentProcessingResult res = processEnrichment(markdown, i, contentStart, type, absolutePosition, enrichments, placeholders, result);
                    if (res != null) {
                        i = res.newI();
                        absolutePosition = res.newAbsolutePosition();
                        continue;
                    }

                    // No closing found: treat as plain text
                    result.append(markdown.charAt(i));
                    i++;
                    absolutePosition++;
                    continue;
                }
            }

            // Default copy behavior
            result.append(markdown.charAt(i));
            i++;
            absolutePosition++;
        }

        return result.toString();
    }

    private boolean isKnownEnrichmentType(String type) {
        return "hint".equals(type) || "reminder".equals(type) || "background".equals(type)
                || "example".equals(type) || "warning".equals(type);
    }

    private String buildEnrichmentHtmlUnified(String type, String content) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"inline-enrichment ").append(type).append("\" data-enrichment-type=\"").append(type).append("\">\n");
        html.append("<div class=\"inline-enrichment-header\">");
        html.append(getIconFor(type));
        html.append("<span>").append(escapeHtml(getTitleFor(type))).append("</span>");
        html.append("</div>\n");
        html.append("<div class=\"enrichment-text\">\n");

        // Parse the enrichment content through the same AST pipeline for consistent lists/code
        String processed = processFragmentForEnrichment(content);
        html.append(processed);

        html.append("</div>\n");
        html.append("</div>");
        return html.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String processFragmentForEnrichment(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            String normalized = MarkdownNormalizer.preNormalizeForListsAndFences(content);
            Node doc = parser.parse(normalized);
            MarkdownAstUtils.stripInlineCitationMarkers(doc);
            String inner = renderer.render(doc);
            // strip surrounding <p> if it’s the only wrapper
            Document d = Jsoup.parseBodyFragment(inner);
            d.outputSettings().prettyPrint(false);
            return d.body().html();
        } catch (Exception e) {
            return "<p>" + escapeHtml(content).replace("\n", "<br>") + "</p>";
        }
    }

    private String getTitleFor(String type) {
        return switch (type) {
            case "hint" -> "Helpful Hints";
            case "warning" -> "Warning";
            case "background" -> "Background Context";
            case "example" -> "Example";
            case "reminder" -> "Important Reminders";
            default -> "Info";
        };
    }

    private String getIconFor(String type) {
        return switch (type) {
            case "hint" -> "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z\"/></svg>";
            case "background" -> "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z\"/></svg>";
            case "reminder" -> "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z\"/></svg>";
            case "warning" -> "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z\"/></svg>";
            case "example" -> "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z\"/></svg>";
            default -> "";
        };
    }

    /**
     * Replaces enrichment placeholders with their HTML content.
     */
    String renderEnrichmentBlocksFromPlaceholders(String html, Map<String, String> placeholders) {
        String result = html;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("<p>" + entry.getKey() + "</p>", entry.getValue());
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private int findEnrichmentEndIndex(String markdown, int start) {
        int j = start;
        boolean innerFence = false;
        while (j < markdown.length()) {
            if (j + 2 < markdown.length() && markdown.charAt(j) == '`' && markdown.charAt(j + 1) == '`' && markdown.charAt(j + 2) == '`') {
                innerFence = !innerFence;
                j += 3;
                continue;
            }
            if (!innerFence && j + 1 < markdown.length() && markdown.charAt(j) == '}' && markdown.charAt(j + 1) == '}') {
                return j;
            }
            j++;
        }
        return -1;
    }

    private MarkdownEnrichment createEnrichment(String type, String content, int absolutePosition) {
        return switch (type) {
            case "hint" -> Hint.create(content, absolutePosition);
            case "warning" -> Warning.create(content, absolutePosition);
            case "background" -> Background.create(content, absolutePosition);
            case "example" -> Example.create(content, absolutePosition);
            case "reminder" -> Reminder.create(content, absolutePosition);
            default -> null;
        };
    }

    private EnrichmentProcessingResult processEnrichment(String markdown, int i, int contentStart, String type, int absolutePosition, List<MarkdownEnrichment> enrichments, Map<String, String> placeholders, StringBuilder result) {
         int j = findEnrichmentEndIndex(markdown, contentStart);
         if (j == -1) return null;
         
         String content = markdown.substring(contentStart, j).trim();
         if (content.isEmpty()) {
             int delta = (j + 2) - i;
             return new EnrichmentProcessingResult(j + 2, absolutePosition + delta);
         }
         
         MarkdownEnrichment enrichment = createEnrichment(type, content, absolutePosition);
         if (enrichment != null) {
              enrichments.add(enrichment);
              String placeholderId = "ENRICHMENT_" + UUID.randomUUID().toString().replace("-", "");
              placeholders.put(placeholderId, buildEnrichmentHtmlUnified(type, content));
              result.append(placeholderId);
         } else {
              result.append(markdown, i, j + 2);
         }
         
         return new EnrichmentProcessingResult(j + 2, absolutePosition + (j + 2 - i));
    }

    private record EnrichmentProcessingResult(int newI, int newAbsolutePosition) {}
}
