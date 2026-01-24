package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.MarkdownProcessingException;
import com.williamcallahan.javachat.service.markdown.ProcessedMarkdown;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for markdown processing with AST-based rendering and caching.
 * Prefer {@link #processStructured(String)} for structured output.
 */
@Service
public class MarkdownService {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownService.class);

    private final UnifiedMarkdownService unifiedService;

    /**
     * Creates a MarkdownService backed by the unified AST processor.
     *
     * @param unifiedService unified markdown processor
     */
    public MarkdownService(UnifiedMarkdownService unifiedService) {
        this.unifiedService = unifiedService;
        logger.info("MarkdownService initialized with UnifiedMarkdownService");
    }

    /**
     * Processes markdown using the AST-based approach with structured output.
     *
     * @param markdownText the markdown text to process
     * @return structured markdown result
     */
    public ProcessedMarkdown processStructured(String markdownText) {
        return unifiedService.process(markdownText);
    }

    /**
     * Legacy markdown rendering path retained for API compatibility.
     *
     * @param markdownText the markdown text to render
     * @return HTML-rendered markdown
     * @deprecated Use {@link #processStructured(String)} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public String render(String markdownText) {
        return renderStructuredHtml(markdownText);
    }

    /**
     * Legacy markdown rendering path for previews retained for API compatibility.
     *
     * @param markdownText the markdown text to render
     * @return HTML-rendered markdown
     * @deprecated Use {@link #processStructured(String)} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public String renderPreview(String markdownText) {
        return renderStructuredHtml(markdownText);
    }

    private String renderStructuredHtml(String markdownText) {
        if (markdownText == null || markdownText.isEmpty()) {
            return "";
        }

        try {
            ProcessedMarkdown processedMarkdown = unifiedService.process(markdownText);
            return processedMarkdown.html();
        } catch (MarkdownProcessingException processingFailure) {
            logger.error("Error processing markdown", processingFailure);
            return escapeHtml(markdownText).replace("\n", "<br />\n");
        }
    }

    /**
     * Get cache statistics for monitoring.
     *
     * @return cache statistics
     * @deprecated Use {@link UnifiedMarkdownService#getCacheStats()} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public CacheStats getCacheStats() {
        UnifiedMarkdownService.CacheStats unifiedStats = unifiedService.getCacheStats();
        return new CacheStats(
            unifiedStats.hitCount(),
            unifiedStats.missCount(),
            unifiedStats.evictionCount(),
            unifiedStats.size()
        );
    }

    /**
     * Clear the render cache.
     *
     * @deprecated Use {@link UnifiedMarkdownService#clearCache()} instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public void clearCache() {
        unifiedService.clearCache();
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
	        /**
	         * Computes the cache hit rate as a fraction between 0.0 and 1.0.
	         */
	        public double hitRate() {
	            long total = hitCount + missCount;
	            return total == 0 ? 0.0 : (double) hitCount / total;
	        }
	    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
