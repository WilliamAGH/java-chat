package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for markdown rendering operations.
 * Provides endpoints for converting markdown to HTML with proper formatting.
 */
@RestController
@RequestMapping("/api/markdown")
@CrossOrigin(origins = "*")
public class MarkdownController {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownController.class);
    
    @Autowired
    private MarkdownService markdownService;
    
    @Autowired
    private UnifiedMarkdownService unifiedMarkdownService;
    
    /**
     * Renders markdown text to HTML. This endpoint uses server-side caching to improve performance
     * for frequently rendered content.
     *
     * @param request A JSON object containing the markdown to render. Expected format:
     *                <pre>{@code
     *                  {
     *                    "content": "Your **markdown** text here."
     *                  }
     *                }</pre>
     * @return A {@link ResponseEntity} containing a {@link Map} with the rendered HTML. On success:
     *         <pre>{@code {"html": "<p>...", "source": "server", "cached": true|false}}</pre>
     */
    @PostMapping(value = "/render", 
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> renderMarkdown(@RequestBody Map<String, String> request) {
        try {
            String markdown = request.get("content");
            
            if (markdown == null || markdown.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "html", "",
                    "source", "server",
                    "cached", false
                ));
            }
            
            logger.debug("Processing markdown of length: {}", markdown.length());
            
            var processed = markdownService.processStructured(markdown);
            
            return ResponseEntity.ok(Map.of(
                "html", processed.html(),
                "source", "server",
                "cached", true,  // Will be true if it was cached
                "citations", processed.citations().size(),
                "enrichments", processed.enrichments().size()
            ));
            
        } catch (Exception e) {
            logger.error("Error rendering markdown", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to render markdown",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Renders markdown text to HTML for a real-time preview. This endpoint does *not* use caching,
     * ensuring the latest content is always rendered.
     *
     * @param request A JSON object containing the markdown to render. Expected format:
     *                <pre>{@code
     *                  {
     *                    "content": "Your **markdown** text here."
     *                  }
     *                }</pre>
     * @return A {@link ResponseEntity} containing a {@link Map} with the rendered HTML. On success:
     *         <pre>{@code {"html": "<p>...", "source": "preview", "cached": false}}</pre>
     */
    @PostMapping(value = "/preview",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> previewMarkdown(@RequestBody Map<String, String> request) {
        try {
            String markdown = request.get("content");
            
            if (markdown == null || markdown.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "html", "",
                    "source", "preview",
                    "cached", false
                ));
            }
            
            var processed = markdownService.processStructured(markdown);
            String html = processed.html();
            
            return ResponseEntity.ok(Map.of(
                "html", html,
                "source", "preview",
                "cached", false
            ));
            
        } catch (Exception e) {
            logger.error("Error rendering preview markdown", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to render preview",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Retrieves statistics about the server-side markdown render cache.
     * Provides metrics like hit count, miss count, size, and hit rate.
     *
     * @return A {@link ResponseEntity} with a {@link Map} containing cache statistics.
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        try {
            var stats = unifiedMarkdownService.getCacheStats();
            
            return ResponseEntity.ok(Map.of(
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "evictionCount", stats.evictionCount(),
                "size", stats.size(),
                "hitRate", String.format("%.2f%%", stats.hitRate() * 100)
            ));
            
        } catch (Exception e) {
            logger.error("Error getting cache stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to get cache stats"
            ));
        }
    }
    
    /**
     * Clears the server-side markdown render cache.
     *
     * @return A {@link ResponseEntity} with a status message.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache() {
        try {
            unifiedMarkdownService.clearCache();
            logger.info("Markdown cache cleared via API");
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Cache cleared successfully"
            ));
            
        } catch (Exception e) {
            logger.error("Error clearing cache", e);
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "message", "Failed to clear cache"
            ));
        }
    }
    
    /**
     * Renders markdown using the new AST-based UnifiedMarkdownService directly.
     * This endpoint provides structured output with type-safe citations and enrichments.
     * 
     * @param request A JSON object containing the markdown to render
     * @return A {@link ResponseEntity} with structured markdown processing results
     */
    @PostMapping(value = "/render/structured", 
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> renderStructured(@RequestBody Map<String, String> request) {
        try {
            String markdown = request.get("content");
            
            if (markdown == null || markdown.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "html", "",
                    "citations", 0,
                    "enrichments", 0,
                    "warnings", 0,
                    "processingTimeMs", 0L,
                    "source", "unified-service"
                ));
            }
            
            logger.debug("Processing markdown with UnifiedMarkdownService, length: {}", markdown.length());
            
            var processed = unifiedMarkdownService.process(markdown);
            
            return ResponseEntity.ok(Map.of(
                "html", processed.html(),
                "citations", processed.citations(),
                "enrichments", processed.enrichments(),
                "warnings", processed.warnings(),
                "processingTimeMs", processed.processingTimeMs(),
                "source", "unified-service",
                "structuredElementCount", processed.getStructuredElementCount(),
                "isClean", processed.isClean()
            ));
            
        } catch (Exception e) {
            logger.error("Error rendering structured markdown", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to render structured markdown",
                "message", e.getMessage(),
                "source", "unified-service"
            ));
        }
    }
}