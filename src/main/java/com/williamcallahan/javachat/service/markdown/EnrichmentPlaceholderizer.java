package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.williamcallahan.javachat.domain.markdown.Background;
import com.williamcallahan.javachat.domain.markdown.Example;
import com.williamcallahan.javachat.domain.markdown.Hint;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import com.williamcallahan.javachat.domain.markdown.Reminder;
import com.williamcallahan.javachat.domain.markdown.Warning;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Extracts inline enrichment markers and replaces them with HTML placeholders.
 */
class EnrichmentPlaceholderizer {

    private static final Logger logger = LoggerFactory.getLogger(EnrichmentPlaceholderizer.class);

    private static final String MARKER_START = "{{";
    private static final String MARKER_END = "}}";
    private static final String PLACEHOLDER_PREFIX = "ENRICHMENT_";

    private final Parser parser;
    private final HtmlRenderer renderer;

    /**
     * Defines supported enrichment marker kinds and their rendering metadata.
     */
    private enum EnrichmentKind {
        HINT("hint", "Helpful Hints", "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z\"/></svg>"),
        BACKGROUND("background", "Background Context", "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z\"/></svg>"),
        REMINDER("reminder", "Important Reminders", "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z\"/></svg>"),
        WARNING("warning", "Warning", "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z\"/></svg>"),
        EXAMPLE("example", "Example", "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z\"/></svg>");

        private final String token;
        private final String title;
        private final String iconHtml;

        EnrichmentKind(String token, String title, String iconHtml) {
            this.token = token;
            this.title = title;
            this.iconHtml = iconHtml;
        }

        String token() {
            return token;
        }

        String title() {
            return title;
        }

        String iconHtml() {
            return iconHtml;
        }

        static Optional<EnrichmentKind> fromToken(String rawToken) {
            if (rawToken == null) {
                return Optional.empty();
            }
            String normalized = AsciiTextNormalizer.toLowerAscii(rawToken.trim());
            for (EnrichmentKind kind : EnrichmentKind.values()) {
                if (kind.token.equals(normalized)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

    }

    EnrichmentPlaceholderizer(Parser parser, HtmlRenderer renderer) {
        this.parser = parser;
        this.renderer = renderer;
    }

    private record EnrichmentContext(
        String markdown,
        List<MarkdownEnrichment> enrichments,
        Map<String, String> placeholders,
        StringBuilder outputBuilder
    ) {}

    String extractAndPlaceholderizeEnrichments(
        String markdown,
        List<MarkdownEnrichment> enrichments,
        Map<String, String> placeholders
    ) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown;
        }

        StringBuilder outputBuilder = new StringBuilder(markdown.length() + 64);
        EnrichmentContext context = new EnrichmentContext(markdown, enrichments, placeholders, outputBuilder);
        CodeFenceStateTracker fenceTracker = new CodeFenceStateTracker();

        int cursor = 0;
        int absolutePosition = 0;

        while (cursor < markdown.length()) {
            boolean isStartOfLine = (cursor == 0) || (markdown.charAt(cursor - 1) == '\n');

            // Check for fence markers at start of line
            if (isStartOfLine) {
                CodeFenceStateTracker.FenceMarker marker = CodeFenceStateTracker.scanFenceMarker(markdown, cursor);
                if (marker != null) {
                    if (!fenceTracker.isInsideFence()) {
                        fenceTracker.enterFence(marker.character(), marker.length());
                    } else if (fenceTracker.wouldCloseFence(marker)) {
                        fenceTracker.exitFence();
                    }

                    for (int offset = 0; offset < marker.length(); offset++) {
                        outputBuilder.append(markdown.charAt(cursor + offset));
                    }
                    cursor += marker.length();
                    absolutePosition += marker.length();
                    continue;
                }
            }

            // Track inline code spans outside fenced code
            if (!fenceTracker.isInsideFence()) {
                CodeFenceStateTracker.BacktickRun backtickRun = CodeFenceStateTracker.scanBacktickRun(markdown, cursor);
                if (backtickRun != null) {
                    int consumed = fenceTracker.processCharacter(markdown, cursor, isStartOfLine);
                    for (int offset = 0; offset < backtickRun.length(); offset++) {
                        outputBuilder.append(markdown.charAt(cursor + offset));
                    }
                    cursor += backtickRun.length();
                    absolutePosition += backtickRun.length();
                    continue;
                }
            }

            // Detect enrichment start only when not inside code fences or inline code
            if (!fenceTracker.isInsideCode() && startsWith(markdown, cursor, MARKER_START)) {
                int typeStartIndex = cursor + 2;
                // skip spaces
                while (typeStartIndex < markdown.length()
                    && Character.isWhitespace(markdown.charAt(typeStartIndex))) {
                    typeStartIndex++;
                }
                // read type token
                int typeEndIndex = typeStartIndex;
                while (typeEndIndex < markdown.length()
                    && Character.isLetter(markdown.charAt(typeEndIndex))) {
                    typeEndIndex++;
                }
                String type = markdown.substring(typeStartIndex, Math.min(typeEndIndex, markdown.length()));
                
                // skip spaces
                int cursorAfterType = typeEndIndex;
                while (cursorAfterType < markdown.length()
                    && Character.isWhitespace(markdown.charAt(cursorAfterType))) {
                    cursorAfterType++;
                }
                boolean hasColon = (cursorAfterType < markdown.length() && markdown.charAt(cursorAfterType) == ':');
                Optional<EnrichmentKind> enrichmentKind = EnrichmentKind.fromToken(type);
                
                if (hasColon && enrichmentKind.isPresent()) {
                    int contentStartIndex = cursorAfterType + 1;
                    if (contentStartIndex < markdown.length() && markdown.charAt(contentStartIndex) == ' ') {
                        contentStartIndex++;
                    }
                    EnrichmentProcessingResult processingResult = processEnrichment(
                        context,
                        cursor,
                        contentStartIndex,
                        enrichmentKind.get(),
                        absolutePosition
                    );
                    
                    if (processingResult != null) {
                        cursor = processingResult.nextIndex();
                        absolutePosition = processingResult.nextAbsolutePosition();
                        continue;
                    }
                    // No closing found: treat as plain text, fall through
                }
            }

            // Default copy behavior
            outputBuilder.append(markdown.charAt(cursor));
            cursor++;
            absolutePosition++;
        }

        return outputBuilder.toString();
    }

    private boolean startsWith(String text, int index, String prefix) {
        return text.startsWith(prefix, index);
    }

    private String buildEnrichmentHtmlUnified(EnrichmentKind kind, String content) {
        Document document = Jsoup.parseBodyFragment("");
        document.outputSettings().prettyPrint(false);

        Element container = document.body().appendElement("div");
        container.addClass("inline-enrichment");
        container.addClass(kind.token());
        container.attr("data-enrichment-type", kind.token());

        Element header = container.appendElement("div").addClass("inline-enrichment-header");
        Document iconFragment = Jsoup.parseBodyFragment(kind.iconHtml());
        for (org.jsoup.nodes.Node iconNode : iconFragment.body().childNodesCopy()) {
            header.appendChild(iconNode);
        }
        header.appendElement("span").text(kind.title());

        Element textContainer = container.appendElement("div").addClass("enrichment-text");
        String processedContent = processFragmentForEnrichment(content);
        Document contentFragment = Jsoup.parseBodyFragment(processedContent);
        contentFragment.outputSettings().prettyPrint(false);
        for (org.jsoup.nodes.Node contentNode : contentFragment.body().childNodesCopy()) {
            textContainer.appendChild(contentNode);
        }

        return container.outerHtml();
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
        String normalized = MarkdownNormalizer.preNormalizeForListsAndFences(content);
        Node doc = parser.parse(normalized);
        MarkdownAstUtils.stripInlineCitationMarkers(doc);
        String innerHtml = renderer.render(doc);
        Document parsedDocument = Jsoup.parseBodyFragment(innerHtml);
        parsedDocument.outputSettings().prettyPrint(false);
        convertNewlinesToBreaks(parsedDocument.body(), false);
        return parsedDocument.body().html();
    }

    private static void convertNewlinesToBreaks(org.jsoup.nodes.Node rootNode, boolean inCodeOrPre) {
        if (rootNode == null) {
            return;
        }

        boolean nextInCodeOrPre = inCodeOrPre;
        if (rootNode instanceof Element elementNode) {
            String tagName = elementNode.tagName();
            if ("pre".equals(tagName) || "code".equals(tagName)) {
                nextInCodeOrPre = true;
            }
        }

        if (rootNode instanceof TextNode textNode && !nextInCodeOrPre) {
            String originalText = textNode.getWholeText();
            if (originalText.indexOf('\n') >= 0) {
                java.util.List<String> segments = splitOnNewlines(originalText);
                for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                    String segmentText = segments.get(segmentIndex);
                    if (!segmentText.isEmpty()) {
                        textNode.before(new TextNode(segmentText));
                    }
                    if (segmentIndex + 1 < segments.size()) {
                        textNode.before(new Element("br"));
                    }
                }
                textNode.remove();
                return;
            }
        }

        for (int childIndex = 0; childIndex < rootNode.childNodeSize(); childIndex++) {
            org.jsoup.nodes.Node childNode = rootNode.childNode(childIndex);
            convertNewlinesToBreaks(childNode, nextInCodeOrPre);
        }
    }

    private static java.util.List<String> splitOnNewlines(String text) {
        java.util.List<String> segments = new java.util.ArrayList<>();
        StringBuilder segmentBuilder = new StringBuilder();
        for (int textIndex = 0; textIndex < text.length(); textIndex++) {
            char character = text.charAt(textIndex);
            if (character == '\n') {
                segments.add(segmentBuilder.toString());
                segmentBuilder.setLength(0);
                continue;
            }
            segmentBuilder.append(character);
        }
        segments.add(segmentBuilder.toString());
        return segments;
    }

    /**
     * Replaces enrichment placeholders with their HTML content.
     */
    String renderEnrichmentBlocksFromPlaceholders(String html, Map<String, String> placeholders) {
        String renderedHtml = html;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            renderedHtml = renderedHtml.replace("<p>" + entry.getKey() + "</p>", entry.getValue());
            renderedHtml = renderedHtml.replace(entry.getKey(), entry.getValue());
        }
        return renderedHtml;
    }

    private int findEnrichmentEndIndex(String markdown, int startIndex) {
        CodeFenceStateTracker fenceTracker = new CodeFenceStateTracker();
        int scanIndex = startIndex;

        while (scanIndex < markdown.length()) {
            boolean isStartOfLine = (scanIndex == startIndex) || (markdown.charAt(scanIndex - 1) == '\n');

            if (isStartOfLine) {
                CodeFenceStateTracker.FenceMarker marker = CodeFenceStateTracker.scanFenceMarker(markdown, scanIndex);
                if (marker != null) {
                    if (!fenceTracker.isInsideFence()) {
                        fenceTracker.enterFence(marker.character(), marker.length());
                    } else if (fenceTracker.wouldCloseFence(marker)) {
                        fenceTracker.exitFence();
                    }
                    scanIndex += marker.length();
                    continue;
                }
            }

            if (!fenceTracker.isInsideFence()) {
                CodeFenceStateTracker.BacktickRun backtickRun = CodeFenceStateTracker.scanBacktickRun(markdown, scanIndex);
                if (backtickRun != null) {
                    fenceTracker.processCharacter(markdown, scanIndex, isStartOfLine);
                    scanIndex += backtickRun.length();
                    continue;
                }
            }

            if (!fenceTracker.isInsideCode() && startsWith(markdown, scanIndex, MARKER_END)) {
                return scanIndex;
            }
            scanIndex++;
        }
        return -1;
    }

    private MarkdownEnrichment createEnrichment(EnrichmentKind kind, String content, int absolutePosition) {
        return switch (kind) {
            case HINT -> Hint.create(content, absolutePosition);
            case WARNING -> Warning.create(content, absolutePosition);
            case BACKGROUND -> Background.create(content, absolutePosition);
            case EXAMPLE -> Example.create(content, absolutePosition);
            case REMINDER -> Reminder.create(content, absolutePosition);
        };
    }

    private EnrichmentProcessingResult processEnrichment(
        EnrichmentContext context,
        int openingIndex,
        int contentStartIndex,
        EnrichmentKind kind,
        int absolutePosition
    ) {
        int closingIndex = findEnrichmentEndIndex(context.markdown, contentStartIndex);
        if (closingIndex == -1) {
            return null;
        }

        String content = context.markdown.substring(contentStartIndex, closingIndex).trim();
        int consumedLength = (closingIndex + 2) - openingIndex;

        if (content.isEmpty()) {
            return new EnrichmentProcessingResult(closingIndex + 2, absolutePosition + consumedLength);
        }

        MarkdownEnrichment enrichment = createEnrichment(kind, content, absolutePosition);
        context.enrichments.add(enrichment);
        
        String placeholderId = PLACEHOLDER_PREFIX + UUID.randomUUID().toString().replace("-", "");
        context.placeholders.put(placeholderId, buildEnrichmentHtmlUnified(kind, content));
        context.outputBuilder.append(placeholderId);

        return new EnrichmentProcessingResult(closingIndex + 2, absolutePosition + consumedLength);
    }

    private record EnrichmentProcessingResult(int nextIndex, int nextAbsolutePosition) {}
}
