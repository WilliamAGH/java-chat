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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Extracts inline enrichment markers and replaces them with HTML placeholders.
 */
class EnrichmentPlaceholderizer {

    private static final String MARKER_START = "{{";
    private static final String MARKER_END = "}}";
    private static final String PLACEHOLDER_PREFIX = "ENRICHMENT_";

    private final Parser parser;
    private final HtmlRenderer renderer;

    /**
     * Defines supported enrichment marker kinds and their rendering metadata.
     */
    private enum EnrichmentKind {
        HINT(
                "hint",
                "Helpful Hints",
                "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z\"/></svg>"),
        BACKGROUND(
                "background",
                "Background Context",
                "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z\"/></svg>"),
        REMINDER(
                "reminder",
                "Important Reminders",
                "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z\"/></svg>"),
        WARNING(
                "warning",
                "Warning",
                "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z\"/></svg>"),
        EXAMPLE(
                "example",
                "Example",
                "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z\"/></svg>");

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
            StringBuilder outputBuilder) {}

    String extractAndPlaceholderizeEnrichments(
            String markdown, List<MarkdownEnrichment> enrichments, Map<String, String> placeholders) {
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
                    fenceTracker.processCharacter(markdown, cursor, isStartOfLine);
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
                EnrichmentParseResult parseResult = parseEnrichmentMarker(markdown, cursor);
                if (parseResult.isValid()) {
                    EnrichmentProcessingResult processingResult = processEnrichment(
                            context, cursor, parseResult.contentStartIndex(), parseResult.kind(), absolutePosition);

                    if (processingResult != null) {
                        cursor = processingResult.nextIndex();
                        absolutePosition = processingResult.nextAbsolutePosition();
                        continue;
                    }
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

    /**
     * Processes markdown content within an enrichment block to HTML.
     *
     * @param content raw markdown content from enrichment block
     * @return rendered HTML
     */
    private String processFragmentForEnrichment(String content) {
        if (content == null || content.isEmpty()) return "";
        String normalized = MarkdownNormalizer.preNormalizeForListsAndFences(content);
        Node doc = parser.parse(normalized);
        MarkdownAstUtils.stripInlineCitationMarkers(doc);
        String innerHtml = renderer.render(doc);
        // Flexmark now handles soft breaks with SOFT_BREAK = "<br />\n",
        // so no additional newline-to-br conversion is needed.
        Document parsedDocument = Jsoup.parseBodyFragment(innerHtml);
        parsedDocument.outputSettings().prettyPrint(false);
        return parsedDocument.body().html();
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
                CodeFenceStateTracker.BacktickRun backtickRun =
                        CodeFenceStateTracker.scanBacktickRun(markdown, scanIndex);
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
            int absolutePosition) {
        int closingIndex = findEnrichmentEndIndex(context.markdown, contentStartIndex);
        if (closingIndex == -1) {
            return null;
        }

        String content =
                context.markdown.substring(contentStartIndex, closingIndex).trim();
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

    /**
     * Result of parsing an enrichment marker opening sequence.
     *
     * @param valid true if a valid enrichment marker was found
     * @param kind the enrichment kind, or null if invalid
     * @param contentStartIndex position where content begins (after the colon)
     */
    private record EnrichmentParseResult(boolean valid, EnrichmentKind kind, int contentStartIndex) {
        static EnrichmentParseResult invalid() {
            return new EnrichmentParseResult(false, null, -1);
        }

        static EnrichmentParseResult of(EnrichmentKind kind, int contentStartIndex) {
            return new EnrichmentParseResult(true, kind, contentStartIndex);
        }

        boolean isValid() {
            return valid;
        }
    }

    /**
     * Parses an enrichment marker at the given position.
     * Expects format: {{kind: where kind is one of the EnrichmentKind tokens.
     *
     * @param markdown the markdown text
     * @param markerStart position of the opening {{
     * @return parse result indicating validity and extracted data
     */
    private EnrichmentParseResult parseEnrichmentMarker(String markdown, int markerStart) {
        int colonIndex = markdown.indexOf(':', markerStart + MARKER_START.length());
        if (colonIndex == -1) {
            return EnrichmentParseResult.invalid();
        }

        String rawToken = markdown.substring(markerStart + MARKER_START.length(), colonIndex);
        return EnrichmentKind.fromToken(rawToken)
                .map(kind -> EnrichmentParseResult.of(kind, colonIndex + 1))
                .orElse(EnrichmentParseResult.invalid());
    }
}
