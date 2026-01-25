package com.williamcallahan.javachat.service.markdown;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.williamcallahan.javachat.domain.markdown.MarkdownCitation;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import com.williamcallahan.javachat.domain.markdown.ProcessedMarkdown;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Unified markdown service that uses AST-based processing instead of regex.
 * This is the AGENTS.md compliant replacement for regex-based markdown processing.
 *
 * Key improvements:
 * - Uses Flexmark AST visitors instead of regex for structured data extraction
 * - Provides type-safe citation and enrichment objects
 * - Maintains backward compatibility during transition
 * - Includes proper error handling and validation
 */
@Service
public class UnifiedMarkdownService {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedMarkdownService.class);
    private static final int MAX_INPUT_LENGTH = 100000; // 100KB max
    private static final int CACHE_SIZE = 500;
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final CitationProcessor citationProcessor;
    private final EnrichmentPlaceholderizer enrichmentPlaceholderizer; // added
    private final Cache<String, ProcessedMarkdown> processCache;

    // Enrichment marker parsing is handled by a streaming scanner (not regex)

    /**
     * Creates the unified markdown processor with AST parsing and caching.
     */
    public UnifiedMarkdownService() {
        // Configure Flexmark with optimal settings
        MutableDataSet options = new MutableDataSet()
                .set(
                        Parser.EXTENSIONS,
                        Arrays.asList(
                                TablesExtension.create(),
                                StrikethroughExtension.create(),
                                TaskListExtension.create(),
                                AutolinkExtension.create()))
                .set(Parser.BLANK_LINES_IN_AST, false)
                .set(Parser.HTML_BLOCK_DEEP_PARSER, false)
                .set(Parser.INDENTED_CODE_NO_TRAILING_BLANK_LINES, true)
                .set(HtmlRenderer.ESCAPE_HTML, true)
                .set(HtmlRenderer.SUPPRESS_HTML, false)
                // Convert soft-breaks (single newlines) to <br> tags to preserve LLM line structure.
                // Matches client-side marked.js with breaks: true for consistent streaming/final render.
                .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
                .set(HtmlRenderer.HARD_BREAK, "<br />\n")
                .set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-")
                .set(HtmlRenderer.SUPPRESSED_LINKS, "(?i)^(javascript|data|vbscript):.*")
                .set(HtmlRenderer.INDENT_SIZE, 2)
                // Strict blank line control: never allow consecutive blank lines in output
                .set(HtmlRenderer.MAX_BLANK_LINES, 0)
                .set(HtmlRenderer.MAX_TRAILING_BLANK_LINES, 0)
                .set(TablesExtension.COLUMN_SPANS, false)
                .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
                .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
                .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
        this.citationProcessor = new CitationProcessor();
        this.enrichmentPlaceholderizer = new EnrichmentPlaceholderizer(parser, renderer); // init

        // Initialize cache
        this.processCache = Caffeine.newBuilder()
                .maximumSize(CACHE_SIZE)
                .expireAfterWrite(CACHE_DURATION)
                .recordStats()
                .build();

        logger.info("UnifiedMarkdownService initialized with AST-based processing");
    }

    /**
     * Processes markdown using AST-based approach instead of regex.
     * This is the main entry point for AGENTS.md compliant markdown processing.
     *
     * @param markdown the markdown text to process
     * @return structured ProcessedMarkdown result
     */
    public ProcessedMarkdown process(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new ProcessedMarkdown("", List.of(), List.of(), List.of(), 0L);
        }

        // Capture original input for consistent cache key before any mutations
        final String cacheKey = markdown;

        // Check cache first, before any transformations
        ProcessedMarkdown cached = processCache.getIfPresent(cacheKey);
        if (cached != null) {
            logger.debug("Cache hit for markdown processing");
            return cached;
        }

        long startTime = System.currentTimeMillis();

        if (markdown.length() > MAX_INPUT_LENGTH) {
            logger.warn("Markdown input exceeds maximum length: {} > {}", markdown.length(), MAX_INPUT_LENGTH);
            markdown = markdown.substring(0, MAX_INPUT_LENGTH);
        }

        // Pre-normalize code fences and heading markers before parsing (no regex)
        markdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdown);

        // Replace enrichment markers with placeholders to prevent cross-node splits (e.g., example code fences)
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        java.util.List<MarkdownEnrichment> placeholderEnrichments = new java.util.ArrayList<>();
        String placeholderMarkdown = enrichmentPlaceholderizer.extractAndPlaceholderizeEnrichments(
                markdown, placeholderEnrichments, placeholders);

        try {
            // Parse markdown to AST - this is the foundation of AGENTS.md compliance
            Node document = parser.parse(placeholderMarkdown);

            // AST-level cleanups prior to HTML rendering
            transformAst(document);

            // Extract structured data using AST visitors (not regex)
            List<MarkdownCitation> citations = citationProcessor.extractCitations(document);
            List<MarkdownEnrichment> enrichments = new java.util.ArrayList<>(placeholderEnrichments);

            // Render HTML from AST
            String html = renderer.render(document);

            // Reinsert enrichment cards from placeholders (handles example blocks)
            html = enrichmentPlaceholderizer.renderEnrichmentBlocksFromPlaceholders(html, placeholders);

            // Post-process HTML using DOM-safe methods
            html = postProcessHtml(html);

            long processingTime = System.currentTimeMillis() - startTime;

            ProcessedMarkdown processedMarkdown = new ProcessedMarkdown(
                    html,
                    citations,
                    enrichments,
                    List.of(), // No warnings for now - will be added in future iterations
                    processingTime);

            // Cache the result using the original input key for consistency
            processCache.put(cacheKey, processedMarkdown);

            logger.debug(
                    "Processed markdown in {}ms: {} citations, {} enrichments",
                    processingTime,
                    citations.size(),
                    enrichments.size());

            return processedMarkdown;

        } catch (Exception processingFailure) {
            logger.error("Error processing markdown with AST approach", processingFailure);
            throw new MarkdownProcessingException("Markdown processing failed", processingFailure);
        }
    }

    // === AST-level transformations ===
    private void transformAst(Node document) {
        if (document == null) return;
        // 1) Strip inline numeric citation markers in Text nodes outside code/links
        MarkdownAstUtils.stripInlineCitationMarkers(document);
        // IMPORTANT: Do not alter author/model list structure. We intentionally disable
        // paragraph-to-list conversions and numeric-heading promotions to preserve
        // ordered lists exactly as authored by the model.
    }

    /**
     * Post-processes HTML using safe string operations.
     * This replaces regex-based post-processing with safer alternatives.
     *
     * @param html the HTML to post-process
     * @return cleaned HTML
     */
    private String postProcessHtml(String html) {
        if (html == null) return "";
        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        // Avoid mutating intra-word spacing; rely on renderer paragraphing
        // Add styling hooks structurally
        for (Element tableElement : document.select("table")) {
            tableElement.addClass("markdown-table");
        }
        for (Element blockquoteElement : document.select("blockquote")) {
            blockquoteElement.addClass("markdown-quote");
        }
        // Remove orphan brace-only paragraphs left by fragmented generations
        for (Element paragraphElement : new java.util.ArrayList<>(document.select("p"))) {
            if (!paragraphElement
                    .parents()
                    .select("pre, code, .inline-enrichment")
                    .isEmpty()) continue;
            String paragraphText = paragraphElement.text();
            if (paragraphText != null) {
                String trimmedParagraphText = paragraphText.trim();
                if (trimmedParagraphText.equals("{") || trimmedParagraphText.equals("}")) {
                    paragraphElement.remove();
                }
            }
        }
        // Inline list normalization happens on the DOM (not regex) so malformed inline list markers
        // like "Key points: 1. First 2. Second" still render as proper <ol>/<ul>.
        renderInlineLists(document);

        // Intrusive spacing and paragraph splitting removed to preserve original formatting
        // and avoid "double line break" visual artifacts.

        String processedHtml = document.body().html();
        // Final sanitization: collapse any consecutive newlines to single newline (no regex)
        processedHtml = collapseConsecutiveNewlines(processedHtml);
        return processedHtml.trim();
    }

    /**
     * Tracks nesting depth of pre/code tags during HTML scanning.
     * Immutable state object updated via withPre/withCode methods.
     */
    private record CodeBlockDepth(int preDepth, int codeDepth) {
        static final CodeBlockDepth ZERO = new CodeBlockDepth(0, 0);

        boolean isInsideCodeBlock() {
            return preDepth > 0 || codeDepth > 0;
        }

        CodeBlockDepth incrementPre() {
            return new CodeBlockDepth(preDepth + 1, codeDepth);
        }

        CodeBlockDepth decrementPre() {
            return new CodeBlockDepth(Math.max(0, preDepth - 1), codeDepth);
        }

        CodeBlockDepth incrementCode() {
            return new CodeBlockDepth(preDepth, codeDepth + 1);
        }

        CodeBlockDepth decrementCode() {
            return new CodeBlockDepth(preDepth, Math.max(0, codeDepth - 1));
        }
    }

    /**
     * Collapses consecutive newlines to single newlines without using regex.
     * Preserves whitespace within pre/code blocks by tracking tag depth.
     *
     * @param html non-null HTML string to process
     * @return processed HTML with collapsed newlines; empty string if input is empty
     * @throws NullPointerException if html is null
     */
    private String collapseConsecutiveNewlines(String html) {
        java.util.Objects.requireNonNull(html, "html must not be null");
        if (html.isEmpty()) {
            return "";
        }
        StringBuilder collapsed = new StringBuilder(html.length());
        boolean lastWasNewline = false;
        CodeBlockDepth depth = CodeBlockDepth.ZERO;
        int cursor = 0;

        while (cursor < html.length()) {
            char currentChar = html.charAt(cursor);

            // Track <pre> and <code> tags to preserve whitespace inside them
            if (currentChar == '<' && cursor + 4 < html.length()) {
                String ahead = html.substring(cursor, Math.min(cursor + 6, html.length()))
                        .toLowerCase(Locale.ROOT);
                if (ahead.startsWith("<pre>") || ahead.startsWith("<pre ")) {
                    depth = depth.incrementPre();
                } else if (ahead.startsWith("</pre>")) {
                    depth = depth.decrementPre();
                } else if (ahead.startsWith("<code>") || ahead.startsWith("<code ")) {
                    depth = depth.incrementCode();
                } else if (ahead.startsWith("</code")) {
                    depth = depth.decrementCode();
                }
            }

            if (currentChar == '\n' && !depth.isInsideCodeBlock()) {
                if (!lastWasNewline) {
                    collapsed.append(currentChar);
                    lastWasNewline = true;
                }
                // Skip consecutive newlines outside code blocks
            } else {
                collapsed.append(currentChar);
                lastWasNewline = false;
            }
            cursor++;
        }
        return collapsed.toString();
    }

    /**
     * Converts paragraphs containing inline list markers into proper UL/OL blocks.
     * Safe DOM approach; requires 2+ markers and never runs inside pre/code.
     */
    private void renderInlineLists(Document document) {
        if (document == null) {
            return;
        }
        java.util.List<Element> paragraphElements = new java.util.ArrayList<>(document.select("p"));
        for (Element paragraphElement : paragraphElements) {
            if (!paragraphElement
                    .parents()
                    .select("pre, code, .inline-enrichment")
                    .isEmpty()) continue;
            // Only transform plain-text paragraphs to avoid breaking links/code spans.
            if (!paragraphElement.children().isEmpty()) continue;

            String rawText = paragraphElement.text();
            if (rawText == null || rawText.isBlank()) continue;

            InlineListParser.Conversion conversion = InlineListParser.tryConvert(rawText);
            if (conversion == null) continue;

            if (!conversion.leadingText().isBlank()) {
                paragraphElement.before(new Element("p").text(conversion.leadingText()));
            }
            paragraphElement.before(conversion.primaryListElement());
            for (Element additionalListElement : conversion.additionalListElements()) {
                paragraphElement.before(additionalListElement);
            }
            if (!conversion.trailingText().isBlank()) {
                paragraphElement.before(new Element("p").text(conversion.trailingText()));
            }
            paragraphElement.remove();
        }
    }

    // === Pre-normalization and paragraph utilities (no regex) ===

    // fixSentenceSpacing and splitLongParagraphs removed to ensure clean rendering

    /**
     * Gets cache statistics for monitoring.
     * @return cache statistics
     */
    public CacheStats getCacheStats() {
        var stats = processCache.stats();
        return new CacheStats(stats.hitCount(), stats.missCount(), stats.evictionCount(), processCache.estimatedSize());
    }

    /**
     * Clears the processing cache.
     */
    public void clearCache() {
        processCache.invalidateAll();
        logger.info("Unified markdown processing cache cleared");
    }

    /**
     * Cache statistics record.
     */
    public record CacheStats(long hitCount, long missCount, long evictionCount, long size) {
        /**
         * Computes the cache hit rate as a fraction between 0.0 and 1.0.
         */
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }
    }
}
