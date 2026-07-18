package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.williamcallahan.javachat.domain.markdown.EnrichmentKindCatalog;
import com.williamcallahan.javachat.domain.markdown.EnrichmentKindCatalog.EnrichmentPresentation;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final EnrichmentKindCatalog enrichmentKindCatalog;

    EnrichmentPlaceholderizer(Parser parser, HtmlRenderer renderer) {
        this(parser, renderer, EnrichmentKindCatalog.load());
    }

    EnrichmentPlaceholderizer(Parser parser, HtmlRenderer renderer, EnrichmentKindCatalog enrichmentKindCatalog) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.enrichmentKindCatalog = Objects.requireNonNull(enrichmentKindCatalog, "enrichmentKindCatalog");
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
        MarkdownBlockContext blockContext = new MarkdownBlockContext();
        int lineStartIndex = 0;
        int absolutePosition = 0;

        while (lineStartIndex < markdown.length()) {
            int lineEndIndex = MarkdownBlockContext.lineEndIndex(markdown, lineStartIndex);
            MarkdownBlockContext.LineContext lineContext =
                    blockContext.classifyLine(markdown, lineStartIndex, lineEndIndex);
            if (lineContext.isCodeBlock()) {
                outputBuilder.append(markdown, lineStartIndex, lineEndIndex);
                absolutePosition += lineEndIndex - lineStartIndex;
            } else {
                int cursor = lineStartIndex;
                while (cursor < lineEndIndex) {
                    int inlineDelimiterLength = blockContext.consumeInlineCodeDelimiter(markdown, cursor);
                    if (inlineDelimiterLength > 0) {
                        outputBuilder.append(markdown, cursor, cursor + inlineDelimiterLength);
                        cursor += inlineDelimiterLength;
                        absolutePosition += inlineDelimiterLength;
                        continue;
                    }

                    if (!blockContext.isInsideCode() && markdown.startsWith(MARKER_START, cursor)) {
                        EnrichmentParseResult parseResult = parseEnrichmentMarker(markdown, cursor);
                        if (parseResult.isValid()) {
                            EnrichmentProcessingResult processingResult = processEnrichment(
                                    context,
                                    cursor,
                                    parseResult.contentStartIndex(),
                                    parseResult.presentation(),
                                    absolutePosition);
                            if (processingResult != null) {
                                cursor = processingResult.nextIndex();
                                absolutePosition = processingResult.nextAbsolutePosition();
                                lineEndIndex = MarkdownBlockContext.lineEndIndex(markdown, cursor);
                                continue;
                            }
                        }
                    }

                    outputBuilder.append(markdown.charAt(cursor));
                    cursor++;
                    absolutePosition++;
                }
            }

            if (lineEndIndex < markdown.length()) {
                outputBuilder.append('\n');
                absolutePosition++;
            }
            lineStartIndex = lineEndIndex + 1;
        }

        return outputBuilder.toString();
    }

    private String buildEnrichmentHtmlUnified(EnrichmentPresentation presentation, String content) {
        Document document = Jsoup.parseBodyFragment("");
        document.outputSettings().prettyPrint(false);

        Element container = document.body().appendElement("div");
        container.addClass("inline-enrichment");
        container.addClass(presentation.token());
        container.attr("data-enrichment-type", presentation.token());

        Element header = container.appendElement("div").addClass("inline-enrichment-header");
        Document iconFragment = Jsoup.parseBodyFragment(presentation.iconHtml());
        for (org.jsoup.nodes.Node iconNode : iconFragment.body().childNodesCopy()) {
            header.appendChild(iconNode);
        }
        header.appendElement("span").text(presentation.title());

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
        MarkdownBlockContext blockContext = new MarkdownBlockContext();
        int scanIndex = startIndex;
        int lineStartIndex = findLineStartIndex(markdown, startIndex);

        while (scanIndex < markdown.length()) {
            int lineEndIndex = MarkdownBlockContext.lineEndIndex(markdown, lineStartIndex);
            if (scanIndex == lineStartIndex) {
                MarkdownBlockContext.LineContext lineContext =
                        blockContext.classifyLine(markdown, lineStartIndex, lineEndIndex);
                if (lineContext.isCodeBlock()) {
                    scanIndex = lineEndIndex;
                }
            }

            while (scanIndex < lineEndIndex) {
                int inlineDelimiterLength = blockContext.consumeInlineCodeDelimiter(markdown, scanIndex);
                if (inlineDelimiterLength > 0) {
                    scanIndex += inlineDelimiterLength;
                    continue;
                }

                if (!blockContext.isInsideCode() && markdown.charAt(scanIndex) == '}') {
                    int closeIndex = resolveCloseIndexFromBraceRun(markdown, scanIndex);
                    if (closeIndex >= 0) {
                        return closeIndex;
                    }
                }
                scanIndex++;
            }

            if (scanIndex < markdown.length()) {
                scanIndex++;
                lineStartIndex = scanIndex;
            }
        }
        return -1;
    }

    private int findLineStartIndex(String markdown, int cursor) {
        int lineStartIndex = cursor;
        while (lineStartIndex > 0 && markdown.charAt(lineStartIndex - 1) != '\n') {
            lineStartIndex--;
        }
        return lineStartIndex;
    }

    /**
     * Resolves a close marker index from a run of closing braces.
     *
     * <p>For runs like {@code }}} this chooses the final {@code }} so a trailing content
     * brace remains part of the enrichment body instead of leaking outside the card.</p>
     *
     * @param markdown source markdown text
     * @param runStart index of a {@code }} candidate run start
     * @return close marker start index, or -1 if no marker exists in the run
     */
    private int resolveCloseIndexFromBraceRun(String markdown, int runStart) {
        int runLength = 0;
        while (runStart + runLength < markdown.length() && markdown.charAt(runStart + runLength) == '}') {
            runLength++;
        }
        if (runLength < MARKER_END.length()) {
            return -1;
        }
        return runStart + (runLength - MARKER_END.length());
    }

    private EnrichmentProcessingResult processEnrichment(
            EnrichmentContext context,
            int openingIndex,
            int contentStartIndex,
            EnrichmentPresentation presentation,
            int absolutePosition) {
        int closingIndex = findEnrichmentEndIndex(context.markdown, contentStartIndex);
        if (closingIndex == -1) {
            return null;
        }

        String enrichmentMarkdown =
                context.markdown.substring(contentStartIndex, closingIndex).strip();
        int consumedLength = (closingIndex + 2) - openingIndex;

        if (MarkdownEnrichment.isBlankEnrichmentText(enrichmentMarkdown)) {
            return new EnrichmentProcessingResult(closingIndex + 2, absolutePosition + consumedLength);
        }

        MarkdownEnrichment enrichment =
                new MarkdownEnrichment(presentation.token(), enrichmentMarkdown, absolutePosition);
        context.enrichments.add(enrichment);

        String placeholderId = PLACEHOLDER_PREFIX + UUID.randomUUID().toString().replace("-", "");
        context.placeholders.put(placeholderId, buildEnrichmentHtmlUnified(presentation, enrichmentMarkdown));
        context.outputBuilder.append(placeholderId);

        return new EnrichmentProcessingResult(closingIndex + 2, absolutePosition + consumedLength);
    }

    private record EnrichmentProcessingResult(int nextIndex, int nextAbsolutePosition) {}

    /**
     * Result of parsing an enrichment marker opening sequence.
     *
     * @param valid true if a valid enrichment marker was found
     * @param presentation canonical presentation, or null if invalid
     * @param contentStartIndex position where content begins (after the colon)
     */
    private record EnrichmentParseResult(boolean valid, EnrichmentPresentation presentation, int contentStartIndex) {
        static EnrichmentParseResult invalid() {
            return new EnrichmentParseResult(false, null, -1);
        }

        static EnrichmentParseResult of(EnrichmentPresentation presentation, int contentStartIndex) {
            return new EnrichmentParseResult(true, presentation, contentStartIndex);
        }

        boolean isValid() {
            return valid;
        }
    }

    /**
     * Parses an enrichment marker at the given position.
     * Expects format: {{kind: where kind is an exact canonical manifest token.
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
        return enrichmentKindCatalog
                .find(rawToken)
                .map(presentation -> EnrichmentParseResult.of(presentation, colonIndex + 1))
                .orElse(EnrichmentParseResult.invalid());
    }
}
