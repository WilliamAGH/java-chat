package com.williamcallahan.javachat.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Service for rendering Markdown to HTML with optimal formatting and caching.
 * Configured for clean output with proper spacing and code block support.
 */
@Service
public class MarkdownService {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownService.class);
    private static final int MAX_INPUT_LENGTH = 100000; // 100KB max
    private static final int CACHE_SIZE = 500;
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);
    
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Cache<String, String> renderCache;
    
    // Pattern for custom enrichment markers
    private static final Pattern ENRICHMENT_PATTERN = Pattern.compile(
        "\\{\\{(hint|reminder|background|example|warning):([\\s\\S]*?)\\}\\}",
        Pattern.MULTILINE
    );
    
    public MarkdownService() {
        // Configure Flexmark with optimal settings
        MutableDataSet options = new MutableDataSet()
            // Core extensions for GitHub Flavored Markdown
            .set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ))
            
            // Parser options
            .set(Parser.BLANK_LINES_IN_AST, false) // Don't preserve blank lines in AST
            .set(Parser.HTML_BLOCK_DEEP_PARSER, false) // Security: don't parse HTML deeply
            .set(Parser.INDENTED_CODE_NO_TRAILING_BLANK_LINES, true) // Clean code blocks
            
            // Renderer options for clean output
            .set(HtmlRenderer.ESCAPE_HTML, true) // Escape raw HTML input for XSS protection
            .set(HtmlRenderer.SUPPRESS_HTML, false) // Allow markdown-generated HTML output
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n") // Line breaks as <br>
            .set(HtmlRenderer.HARD_BREAK, "<br />\n") // Consistent line breaks
            .set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-") // For Prism.js
            .set(HtmlRenderer.INDENT_SIZE, 2) // Clean indentation
            
            // Table rendering options
            .set(TablesExtension.COLUMN_SPANS, false)
            .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
            .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
            .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);
        
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
        
        // Initialize cache
        this.renderCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION)
            .recordStats()
            .build();
        
        logger.info("MarkdownService initialized with Flexmark and caching");
    }
    
    /**
     * Renders markdown to HTML with caching and optimal formatting.
     * 
     * @param markdown The markdown text to render
     * @return Clean HTML output with proper spacing
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        if (markdown.length() > MAX_INPUT_LENGTH) {
            logger.warn("Markdown input exceeds maximum length: {} > {}", 
                       markdown.length(), MAX_INPUT_LENGTH);
            markdown = markdown.substring(0, MAX_INPUT_LENGTH);
        }
        
        // Check cache first
        String cached = renderCache.getIfPresent(markdown);
        if (cached != null) {
            logger.debug("Cache hit for markdown rendering");
            return cached;
        }
        
        try {
            // Pre-process to fix common markdown formatting issues
            markdown = preprocessMarkdown(markdown);
            
            // Pre-process custom enrichments (preserve them)
            String preprocessed = preserveEnrichments(markdown);
            
            // Parse and render markdown
            Node document = parser.parse(preprocessed);
            String html = renderer.render(document);
            
            // Post-process for clean output
            html = postProcessHtml(html);
            
            // Restore custom enrichments
            html = restoreEnrichments(html);
            
            // Cache the result
            renderCache.put(markdown, html);
            
            return html;
        } catch (Exception e) {
            logger.error("Error rendering markdown", e);
            // Fallback to escaped plain text
            return escapeHtml(markdown).replace("\n", "<br />\n");
        }
    }
    
    /**
     * Renders markdown without caching (for preview/draft content).
     */
    public String renderPreview(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        try {
            // Pre-process to fix common markdown formatting issues
            markdown = preprocessMarkdown(markdown);
            String preprocessed = preserveEnrichments(markdown);
            Node document = parser.parse(preprocessed);
            String html = renderer.render(document);
            html = postProcessHtml(html);
            html = restoreEnrichments(html);
            return html;
        } catch (Exception e) {
            logger.error("Error rendering preview markdown", e);
            return escapeHtml(markdown).replace("\n", "<br />\n");
        }
    }
    
    /**
     * Pre-processes markdown to fix common formatting issues.
     * Ensures lists and code blocks are properly separated from preceding text.
     */
    public String preprocessMarkdown(String markdown) {
        // CRITICAL: Ensure single space after sentence-ending punctuation FIRST
        // This must happen before paragraph breaking so the text can be properly split
        // Fixes: "operation.For example" -> "operation. For example"
        // Also handles quotes: "operator."This -> "operator." This
        markdown = markdown.replaceAll("([.!?])([\"'])?([A-Z])", "$1$2 $3");
        
        // Apply smart paragraph breaking AFTER fixing spaces
        markdown = applySmartParagraphBreaks(markdown);
        
        // DO NOT auto-detect lists from inline text like "are:1."
        // Only treat as list if it's at the start of a line or after explicit list marker
        // This prevents "are:1. boolean" from becoming a list
        // markdown = markdown.replaceAll(":\\s+(\\d+\\.\\s+[A-Z])", ":\n\n$1");
        // COMMENTED OUT - causing false positives
        
        // Fix inline bullet lists: "text:- " or "text: - " -> "text:\n\n- "
        markdown = markdown.replaceAll("([^\\n]):?-\\s+", "$1\n\n- ");
        markdown = markdown.replaceAll("([^\\n]):\\*\\s+", "$1\n\n* ");
        markdown = markdown.replaceAll("([^\\n]):\\+\\s+", "$1\n\n+ ");
        
        // Fix code blocks without preceding line break: "text:```" -> "text:\n\n```"
        markdown = markdown.replaceAll("([^\\n]):?\\s?```", "$1\n\n```");
        
        // CRITICAL: Protect code block content from being consumed by other patterns
        // Ensure code blocks are properly delimited and content is preserved
        markdown = protectCodeBlocks(markdown);
        
        // Clean up: Never allow multiple consecutive HORIZONTAL spaces (preserve newlines!)
        markdown = markdown.replaceAll("[ \\t]{2,}", " ");
        
        // Never allow leading spaces at start of lines (except in code blocks)
        markdown = markdown.replaceAll("(?m)^[ \\t]+(?!```)", "");
        
        return markdown;
    }
    
    /**
     * Intelligently breaks long text into paragraphs.
     * Avoids breaking in inappropriate places like abbreviations or code.
     */
    private String applySmartParagraphBreaks(String markdown) {
        // Don't process code blocks
        if (markdown.contains("```")) {
            return markdown;
        }
        
        // CRITICAL: We MUST add paragraph breaks for proper rendering!
        
        // First check if there are already paragraph breaks
        if (markdown.contains("\n\n")) {
            // Already has breaks, don't add more
            return markdown;
        }
        
        // Split on sentence endings but keep the punctuation
        String[] sentences = markdown.split("(?<=\\. )");
        
        // If text is very short, don't break it
        if (sentences.length < 2) {
            return markdown;
        }
        
        StringBuilder result = new StringBuilder();
        int sentenceCount = 0;
        
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i];
            result.append(sentence);
            sentenceCount++;
            
            // Add paragraph break every 2-3 sentences
            // Check various conditions for breaking
            boolean shouldBreak = false;
            
            if (sentenceCount >= 2 && i < sentences.length - 1) {
                // Don't break at abbreviations
                if (!sentence.endsWith("e.g. ") && 
                    !sentence.endsWith("i.e. ") && 
                    !sentence.endsWith("etc. ") &&
                    !sentence.endsWith("Dr. ") &&
                    !sentence.endsWith("Mr. ") &&
                    !sentence.endsWith("Mrs. ") &&
                    !sentence.endsWith("Ms. ")) {
                    shouldBreak = true;
                    sentenceCount = 0;
                }
            }
            
            if (shouldBreak) {
                // ADD THE CRITICAL DOUBLE NEWLINE FOR PARAGRAPH BREAK
                result.append("\n\n");
                logger.debug("Added paragraph break after: {}", 
                           sentence.substring(Math.max(0, sentence.length() - 30)));
            }
        }
        
        String processed = result.toString().trim();
        logger.debug("Paragraph breaking: {} sentences -> {} paragraphs", 
                    sentences.length, processed.split("\n\n").length);
        return processed;
    }
    
    /**
     * Protects code block content from being consumed or altered by other regex patterns.
     * Ensures code blocks maintain their content integrity.
     */
    private String protectCodeBlocks(String markdown) {
        // Match code blocks and ensure they have proper structure
        StringBuilder result = new StringBuilder();
        java.util.regex.Pattern codeBlockPattern = 
            java.util.regex.Pattern.compile("```([a-z]*)\n?([^`]*?)```", 
                                           java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = codeBlockPattern.matcher(markdown);
        
        int lastEnd = 0;
        while (matcher.find()) {
            // Append text before code block
            result.append(markdown.substring(lastEnd, matcher.start()));
            
            String language = matcher.group(1);
            String code = matcher.group(2);
            
            // Ensure code content is not empty and properly formatted
            if (code != null && !code.trim().isEmpty()) {
                // Preserve the code block with proper formatting
                result.append("```").append(language).append("\n");
                result.append(code);
                if (!code.endsWith("\n")) {
                    result.append("\n");
                }
                result.append("```\n");
                logger.debug("Code block preserved with {} characters", code.length());
            } else {
                // Log warning about empty code block
                logger.warn("Empty code block detected at position {}, language: '{}'", 
                           matcher.start(), language);
                result.append("```").append(language).append("\n");
                result.append("// Code block content missing - check streaming\n");
                result.append("```\n");
            }
            
            lastEnd = matcher.end();
        }
        
        // Append remaining text
        if (lastEnd < markdown.length()) {
            result.append(markdown.substring(lastEnd));
        }
        
        return result.toString();
    }
    
    /**
     * Post-processes HTML for optimal spacing and formatting.
     */
    private String postProcessHtml(String html) {
        // CRITICAL: Ensure space between sentences in HTML content
        // Fixes cases where tags might have removed spaces
        html = html.replaceAll("([.!?])(<[^>]+>)?([A-Z])", "$1$2 $3");
        
        // Remove any leading spaces from paragraph starts
        html = html.replaceAll("<p>\\s+", "<p>");
        
        // IMPORTANT: Preserve line breaks - only collapse excessive ones (more than 3)
        // This maintains intentional paragraph breaks
        html = html.replaceAll("(\n\\s*){4,}", "\n\n\n");
        
        // Ensure proper paragraph spacing (maintain separation)
        html = html.replaceAll("</p>\\s*<p>", "</p>\n\n<p>");
        
        // Clean up list spacing
        html = html.replaceAll("</li>\\s*<li>", "</li>\n<li>");
        
        // Remove only truly empty paragraphs (no content at all)
        html = html.replaceAll("<p>\\s*</p>", "");
        
        // Ensure proper spacing around code blocks with ALL elements
        html = html.replaceAll("</pre>\\s*<p>", "</pre>\n\n<p>");
        html = html.replaceAll("</p>\\s*<pre>", "</p>\n\n<pre>");
        html = html.replaceAll("</ol>\\s*<pre>", "</ol>\n\n<pre>");
        html = html.replaceAll("</ul>\\s*<pre>", "</ul>\n\n<pre>");
        html = html.replaceAll("</pre>\\s*<ol>", "</pre>\n\n<ol>");
        html = html.replaceAll("</pre>\\s*<ul>", "</pre>\n\n<ul>");
        
        // Ensure proper spacing between paragraphs and lists
        html = html.replaceAll("</p>\\s*<ol>", "</p>\n\n<ol>");
        html = html.replaceAll("</p>\\s*<ul>", "</p>\n\n<ul>");
        html = html.replaceAll("</ol>\\s*<p>", "</ol>\n\n<p>");
        html = html.replaceAll("</ul>\\s*<p>", "</ul>\n\n<p>");
        
        // Ensure proper spacing around enrichment placeholders (now text placeholders)
        html = html.replaceAll("(ZZENRICHZ\\w+ZSTARTZZZ)", "\n$1");
        html = html.replaceAll("(ZZENRICHZ\\w+ZENDZZZ)", "$1\n");
        
        // Clean up table formatting
        html = html.replaceAll("</tr>\\s*<tr>", "</tr>\n<tr>");
        
        // Add classes for styling
        html = html.replace("<table>", "<table class=\"markdown-table\">");
        html = html.replace("<blockquote>", "<blockquote class=\"markdown-quote\">");
        
        return html.trim();
    }
    
    /**
     * Preserves custom enrichment markers during markdown processing.
     * Uses unique placeholders that won't be affected by markdown parsing or HTML filtering.
     */
    private String preserveEnrichments(String markdown) {
        // Log if we're about to process enrichments
        if (markdown.contains("{{")) {
            logger.debug("Processing enrichment markers in markdown");
        }
        
        // Replace enrichment markers with unique placeholders that won't be processed by markdown
        // Using a format that avoids markdown special characters (no underscores, asterisks, etc.)
        return ENRICHMENT_PATTERN.matcher(markdown).replaceAll(
            "ZZENRICHZ$1ZSTARTZZZ$2ZZENRICHZ$1ZENDZZZ"
        );
    }
    
    /**
     * Restores custom enrichment markers after markdown processing.
     * Works with unique text placeholders that survive HTML processing.
     */
    private String restoreEnrichments(String html) {
        // Restore from unique text placeholders
        // Pattern: ZZENRICHZ(type)ZSTARTZZZ(content)ZZENRICHZ(type)ZENDZZZ
        html = html.replaceAll(
            "ZZENRICHZ(\\w+)ZSTARTZZZ([\\s\\S]*?)ZZENRICHZ\\1ZENDZZZ",
            "{{$1:$2}}"
        );
        
        // Clean up any HTML entities that might have been escaped in the content
        html = html.replace("&quot;", "\"");
        html = html.replace("&apos;", "'");
        html = html.replace("&#39;", "'");
        
        return html;
    }
    
    /**
     * Escapes HTML for security.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
    
    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getCacheStats() {
        var stats = renderCache.stats();
        return new CacheStats(
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount(),
            renderCache.estimatedSize()
        );
    }
    
    /**
     * Clear the render cache.
     */
    public void clearCache() {
        renderCache.invalidateAll();
        logger.info("Markdown render cache cleared");
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
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }
    }
    
}