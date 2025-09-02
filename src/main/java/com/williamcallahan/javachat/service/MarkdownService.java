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
            .set(HtmlRenderer.ESCAPE_HTML, true) // XSS protection
            .set(HtmlRenderer.SUPPRESS_HTML, true) // Don't allow raw HTML
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
     * Post-processes HTML for optimal spacing and formatting.
     */
    private String postProcessHtml(String html) {
        // Remove excessive blank lines (more than 2 consecutive)
        html = html.replaceAll("(\n\\s*){3,}", "\n\n");
        
        // Clean up paragraph spacing - ensure single spacing
        html = html.replaceAll("</p>\\s*<p>", "</p>\n<p>");
        
        // Clean up list spacing
        html = html.replaceAll("</li>\\s*<li>", "</li>\n<li>");
        
        // Remove empty paragraphs
        html = html.replaceAll("<p>\\s*</p>", "");
        
        // Ensure code blocks have proper spacing
        html = html.replaceAll("</pre>\\s*<p>", "</pre>\n<p>");
        html = html.replaceAll("</p>\\s*<pre>", "</p>\n<pre>");
        
        // Clean up table formatting
        html = html.replaceAll("</tr>\\s*<tr>", "</tr>\n<tr>");
        
        // Add classes for styling
        html = html.replace("<table>", "<table class=\"markdown-table\">");
        html = html.replace("<blockquote>", "<blockquote class=\"markdown-quote\">");
        
        return html.trim();
    }
    
    /**
     * Preserves custom enrichment markers during markdown processing.
     */
    private String preserveEnrichments(String markdown) {
        // Replace enrichment markers with placeholders
        return ENRICHMENT_PATTERN.matcher(markdown).replaceAll(
            "__ENRICHMENT_$1_START__$2__ENRICHMENT_$1_END__"
        );
    }
    
    /**
     * Restores custom enrichment markers after markdown processing.
     */
    private String restoreEnrichments(String html) {
        // First, handle cases where the whole thing got wrapped in <strong> tags
        // Like: <strong>ENRICHMENT_hint_START__text__ENRICHMENT_hint_END</strong>
        html = html.replaceAll(
            "<strong>ENRICHMENT_(\\w+)_START__([\\s\\S]*?)__ENRICHMENT_\\1_END</strong>",
            "{{$1:$2}}"
        );
        
        // Handle partially wrapped cases with missing underscores
        // Like: __ENRICHMENT_example_START__text<strong>ENRICHMENT_example_END</strong>
        html = html.replaceAll(
            "__ENRICHMENT_(\\w+)_START__([\\s\\S]*?)<strong>ENRICHMENT_\\1_END</strong>",
            "{{$1:$2}}"
        );
        
        // Handle any remaining unwrapped cases
        html = html.replaceAll(
            "__ENRICHMENT_(\\w+)_START__([\\s\\S]*?)__ENRICHMENT_\\1_END__",
            "{{$1:$2}}"
        );
        
        // Clean up any HTML entities that might have been escaped in the content
        html = html.replace("&quot;", "\"");
        
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