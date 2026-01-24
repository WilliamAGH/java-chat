package com.williamcallahan.javachat.service.markdown;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.williamcallahan.javachat.domain.markdown.MarkdownCitation;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import com.williamcallahan.javachat.domain.markdown.ProcessedMarkdown;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

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
            .set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ))
            .set(Parser.BLANK_LINES_IN_AST, false)
            .set(Parser.HTML_BLOCK_DEEP_PARSER, false)
            .set(Parser.INDENTED_CODE_NO_TRAILING_BLANK_LINES, true)
            .set(HtmlRenderer.ESCAPE_HTML, true)
            .set(HtmlRenderer.SUPPRESS_HTML, false)
            // Preserve soft-breaks as plain newlines so browsers treat them as spaces, avoiding forced <br/>
            .set(HtmlRenderer.SOFT_BREAK, "\n")
            .set(HtmlRenderer.HARD_BREAK, "<br />\n")
            .set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-")
            .set(HtmlRenderer.SUPPRESSED_LINKS, "(?i)^(javascript|data|vbscript):.*")
            .set(HtmlRenderer.INDENT_SIZE, 2)
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
            logger.warn("Markdown input exceeds maximum length: {} > {}",
                       markdown.length(), MAX_INPUT_LENGTH);
            markdown = markdown.substring(0, MAX_INPUT_LENGTH);
        }

        // Pre-normalize code fences and heading markers before parsing (no regex)
        markdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdown);

        // Replace enrichment markers with placeholders to prevent cross-node splits (e.g., example code fences)
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        java.util.List<MarkdownEnrichment> placeholderEnrichments = new java.util.ArrayList<>();
        String placeholderMarkdown = enrichmentPlaceholderizer.extractAndPlaceholderizeEnrichments(markdown, placeholderEnrichments, placeholders);
        
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
                processingTime
            );
            
            // Cache the result using the original input key for consistency
            processCache.put(cacheKey, processedMarkdown);
            
            logger.debug("Processed markdown in {}ms: {} citations, {} enrichments", 
                        processingTime, citations.size(), enrichments.size());
            
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
            if (!paragraphElement.parents().select("pre, code, .inline-enrichment").isEmpty()) continue;
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
        return processedHtml.trim();
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
            if (!paragraphElement.parents().select("pre, code, .inline-enrichment").isEmpty()) continue;
            // Only transform plain-text paragraphs to avoid breaking links/code spans.
            if (!paragraphElement.children().isEmpty()) continue;

            String rawText = paragraphElement.text();
            if (rawText == null || rawText.isBlank()) continue;

            InlineListConversion conversion = InlineListConversion.tryConvert(rawText);
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

    private record InlineListConversion(
        String leadingText,
        Element primaryListElement,
        java.util.List<Element> additionalListElements,
        String trailingText
    ) {
        static InlineListConversion tryConvert(String text) {
            InlineListParse parse = InlineListParse.tryParse(text);
            if (parse == null) {
                return null;
            }

            Element listElement = new Element(parse.primaryBlock().tagName());
            for (String itemLabel : parse.primaryBlock().itemLabels()) {
                listElement.appendChild(new Element("li").text(itemLabel));
            }

            java.util.List<Element> additionalLists = new java.util.ArrayList<>();
            for (String nestedSegment : parse.nestedSegments()) {
                InlineListParse nestedParse = InlineListParse.tryParse(nestedSegment);
                if (nestedParse == null) continue;
                Element nestedListElement = new Element(nestedParse.primaryBlock().tagName());
                for (String itemLabel : nestedParse.primaryBlock().itemLabels()) {
                    nestedListElement.appendChild(new Element("li").text(itemLabel));
                }
                additionalLists.add(nestedListElement);
                additionalLists.addAll(renderNestedListsRecursively(nestedParse, 1));
            }

            return new InlineListConversion(parse.leadingText(), listElement, additionalLists, parse.trailingText());
        }

        private static java.util.List<Element> renderNestedListsRecursively(InlineListParse parse, int depth) {
            if (depth >= 3) {
                return java.util.List.of();
            }
            java.util.List<Element> listElements = new java.util.ArrayList<>();
            for (String nestedSegment : parse.nestedSegments()) {
                InlineListParse nestedParse = InlineListParse.tryParse(nestedSegment);
                if (nestedParse == null) continue;
                Element nestedListElement = new Element(nestedParse.primaryBlock().tagName());
                for (String itemLabel : nestedParse.primaryBlock().itemLabels()) {
                    nestedListElement.appendChild(new Element("li").text(itemLabel));
                }
                listElements.add(nestedListElement);
                listElements.addAll(renderNestedListsRecursively(nestedParse, depth + 1));
            }
            return listElements;
        }
    }

    private record InlineListBlock(String tagName, java.util.List<String> itemLabels) {}

    private record InlineListParse(
        String leadingText,
        InlineListBlock primaryBlock,
        java.util.List<String> nestedSegments,
        String trailingText
	    ) {
	        static InlineListParse tryParse(String input) {
	            if (input == null) return null;
	            String text = input.strip();
	            if (text.isEmpty()) return null;

            InlineListParse ordered = tryParseOrdered(text);
            if (ordered != null) return ordered;
            return tryParseBulleted(text);
	        }

	        private record Marker(int markerStartIndex, int contentStartIndex) {}

        private static InlineListParse tryParseOrdered(String text) {
            InlineListParse numeric = parseInlineListOrderedKind(text, InlineListOrderedKind.NUMERIC);
            if (numeric != null) return numeric;

            InlineListParse roman = parseInlineListOrderedKind(text, InlineListOrderedKind.ROMAN_LOWER);
            if (roman != null) return roman;

            return parseInlineListOrderedKind(text, InlineListOrderedKind.LETTER_LOWER);
        }

        private static InlineListParse parseInlineListOrderedKind(String text, InlineListOrderedKind kind) {
            java.util.List<Marker> markers = findOrderedMarkers(text, kind);
            if (markers.size() < 2) return null;

            int firstMarkerIndex = markers.get(0).markerStartIndex();
            String leading = text.substring(0, firstMarkerIndex).trim();

            java.util.List<String> itemLabels = new java.util.ArrayList<>();
            java.util.List<String> nestedSegments = new java.util.ArrayList<>();
            for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
                int contentStart = markers.get(markerIndex).contentStartIndex();
                int nextMarkerStart = markerIndex + 1 < markers.size() ? markers.get(markerIndex + 1).markerStartIndex() : text.length();
                String rawItem = text.substring(contentStart, nextMarkerStart).trim();
                if (rawItem.isEmpty()) continue;

                ParsedItem parsedItem = splitNestedList(rawItem);
                itemLabels.add(parsedItem.label());
                if (parsedItem.nestedSegment() != null && !parsedItem.nestedSegment().isBlank()) {
                    nestedSegments.add(parsedItem.nestedSegment());
                }
            }

            if (itemLabels.size() < 2) return null;

            InlineListBlock primaryBlock = new InlineListBlock("ol", itemLabels);
            return new InlineListParse(leading, primaryBlock, nestedSegments, "");
        }

        private static InlineListParse tryParseBulleted(String text) {
            InlineListBulletKind bulletKind = findFirstInlineListBulletKind(text);
            if (bulletKind == null) return null;

            java.util.List<Marker> markers = findBulletMarkers(text, bulletKind);
            if (markers.size() < 2) return null;

            int firstMarkerIndex = markers.get(0).markerStartIndex();
            String leading = text.substring(0, firstMarkerIndex).trim();

            java.util.List<String> itemLabels = new java.util.ArrayList<>();
            java.util.List<String> nestedSegments = new java.util.ArrayList<>();
            for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
                int contentStart = markers.get(markerIndex).contentStartIndex();
                int nextMarkerStart = markerIndex + 1 < markers.size() ? markers.get(markerIndex + 1).markerStartIndex() : text.length();
                String rawItem = text.substring(contentStart, nextMarkerStart).trim();
                if (rawItem.isEmpty()) continue;
                ParsedItem parsedItem = splitNestedList(rawItem);
                itemLabels.add(parsedItem.label());
                if (parsedItem.nestedSegment() != null && !parsedItem.nestedSegment().isBlank()) {
                    nestedSegments.add(parsedItem.nestedSegment());
                }
            }

            if (itemLabels.size() < 2) return null;

            InlineListBlock primaryBlock = new InlineListBlock("ul", itemLabels);
            return new InlineListParse(leading, primaryBlock, nestedSegments, "");
        }

        private static InlineListBulletKind findFirstInlineListBulletKind(String text) {
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                InlineListBulletKind kind = bulletKind(character);
                if (kind == null) continue;
                if (isBulletListIntro(text, index) && hasSecondBulletMarker(text, kind, index + 1)) {
                    return kind;
                }
            }
            return null;
        }

        private static boolean hasSecondBulletMarker(String text, InlineListBulletKind kind, int startIndex) {
            for (int index = startIndex; index < text.length(); index++) {
                if (text.charAt(index) == kind.markerChar() && isBulletMarker(text, index, kind)) {
                    return true;
                }
            }
            return false;
        }

	        private static InlineListBulletKind bulletKind(char character) {
	            return switch (character) {
	                case '-' -> InlineListBulletKind.DASH;
	                case '*' -> InlineListBulletKind.ASTERISK;
	                case '+' -> InlineListBulletKind.PLUS;
	                case '•' -> InlineListBulletKind.BULLET;
	                default -> null;
	            };
	        }

	        private static boolean isBulletListIntro(String text, int markerIndex) {
	            if (markerIndex == 0) return true;
	            char previousChar = text.charAt(markerIndex - 1);
	            return previousChar == ':'
	                || previousChar == '\n'
	                || (previousChar == ' ' && markerIndex >= 2 && text.charAt(markerIndex - 2) == ':');
	        }

	        private static boolean isBulletMarker(String text, int markerIndex, InlineListBulletKind kind) {
	            // Require a space after the marker to avoid catching punctuation/minus uses.
	            return text.charAt(markerIndex) == kind.markerChar()
	                && markerIndex + 1 < text.length()
	                && text.charAt(markerIndex + 1) == ' ';
	        }

        private static java.util.List<Marker> findBulletMarkers(String text, InlineListBulletKind kind) {
            java.util.List<Marker> markers = new java.util.ArrayList<>();
            boolean hasIntro = false;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) != kind.markerChar()) continue;
                if (!hasIntro) {
                    if (!isBulletListIntro(text, index)) continue;
                    if (!isBulletMarker(text, index, kind)) continue;
                    hasIntro = true;
                    markers.add(new Marker(index, index + 2));
                    continue;
                }
                if (isBulletMarker(text, index, kind)) {
                    markers.add(new Marker(index, index + 2));
                }
            }
            return markers;
        }

        private static java.util.List<Marker> findOrderedMarkers(String text, InlineListOrderedKind kind) {
            java.util.List<Marker> markers = new java.util.ArrayList<>();
            int index = 0;
            while (index < text.length()) {
                Marker marker = tryReadOrderedMarkerAt(text, index, kind);
                if (marker != null) {
                    markers.add(marker);
                    index = marker.contentStartIndex();
                    continue;
                }
                index++;
            }
            return markers;
        }

        private static Marker tryReadOrderedMarkerAt(String text, int index, InlineListOrderedKind kind) {
            if (index < 0 || index >= text.length()) return null;
            if (!isMarkerBoundary(text, index)) return null;

            return switch (kind) {
                case NUMERIC -> tryReadNumericMarker(text, index);
                case ROMAN_LOWER -> tryReadRomanMarker(text, index);
                case LETTER_LOWER -> tryReadLetterMarker(text, index);
            };
        }

        private static boolean isMarkerBoundary(String text, int index) {
            if (index == 0) return true;
            char previousChar = text.charAt(index - 1);
            return !Character.isLetterOrDigit(previousChar);
        }

        private static Marker tryReadNumericMarker(String text, int index) {
            int cursor = index;
            int digitCount = 0;
            while (cursor < text.length() && Character.isDigit(text.charAt(cursor)) && digitCount < 3) {
                digitCount++;
                cursor++;
            }
            if (digitCount == 0) return null;
            if (cursor >= text.length()) return null;
            char markerChar = text.charAt(cursor);
            if (markerChar != '.' && markerChar != ')') return null;
            if (cursor + 1 >= text.length()) return null;
            char nextChar = text.charAt(cursor + 1);
            if (Character.isDigit(nextChar)) return null; // version number like 1.8
            int contentStart = cursor + 1;
            if (nextChar == ' ') {
                contentStart = cursor + 2;
            }
            if (contentStart >= text.length()) return null;
            if (!Character.isLetter(text.charAt(contentStart))) return null;
            return new Marker(index, contentStart);
        }

        private static Marker tryReadLetterMarker(String text, int index) {
            char letter = text.charAt(index);
            if (letter < 'a' || letter > 'z') return null;
            if (index + 1 >= text.length()) return null;
            char markerChar = text.charAt(index + 1);
            if (markerChar != '.' && markerChar != ')') return null;
            int contentStart = index + 2;
            if (contentStart < text.length() && text.charAt(contentStart) == ' ') {
                contentStart++;
            }
            if (contentStart >= text.length()) return null;
            if (!Character.isLetter(text.charAt(contentStart))) return null;
            return new Marker(index, contentStart);
        }

        private static Marker tryReadRomanMarker(String text, int index) {
            int cursor = index;
            int length = 0;
            while (cursor < text.length() && length < 6) {
                char c = text.charAt(cursor);
                if (c != 'i' && c != 'v' && c != 'x') break;
                length++;
                cursor++;
            }
            if (length == 0) return null;
            if (cursor >= text.length()) return null;
            char markerChar = text.charAt(cursor);
            if (markerChar != '.' && markerChar != ')') return null;
            int contentStart = cursor + 1;
            if (contentStart < text.length() && text.charAt(contentStart) == ' ') {
                contentStart++;
            }
            if (contentStart >= text.length()) return null;
            if (!Character.isLetter(text.charAt(contentStart))) return null;
            return new Marker(index, contentStart);
        }

        private record ParsedItem(String label, String nestedSegment) {}

        private static ParsedItem splitNestedList(String rawItemText) {
            String trimmed = rawItemText.trim();
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex > 0 && colonIndex < trimmed.length() - 1) {
                String labelPart = trimmed.substring(0, colonIndex).trim();
                String tail = trimmed.substring(colonIndex + 1).trim();
                if (InlineListParse.tryParse(tail) != null) {
                    return new ParsedItem(normalizeItemLabel(labelPart), tail);
                }
            }
            return new ParsedItem(normalizeItemLabel(trimmed), null);
        }

        private static String normalizeItemLabel(String raw) {
            String label = raw == null ? "" : raw.trim();
            int colonIndex = label.indexOf(':');
            if (colonIndex > 0) {
                label = label.substring(0, colonIndex).trim();
            }
            while (!label.isEmpty()) {
                char last = label.charAt(label.length() - 1);
                if (last == '.' || last == '!' || last == '?') {
                    label = label.substring(0, label.length() - 1).trim();
                } else {
                    break;
                }
            }
            return label;
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
        return new CacheStats(
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount(),
            processCache.estimatedSize()
        );
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
	    public record CacheStats(
	        long hitCount,
	        long missCount,
	        long evictionCount,
	        long size
	    ) {
	        /**
	         * Computes the cache hit rate as a fraction between 0.0 and 1.0.
	         */
	        public double hitRate() {
	            long total = hitCount + missCount;
	            return total == 0 ? 0.0 : (double) hitCount / total;
	        }
	    }

}
