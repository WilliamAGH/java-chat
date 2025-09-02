package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.MarkdownService;
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
    
    /**
     * Renders markdown to HTML with caching.
     * 
     * @param request Map containing 'content' key with markdown text
     * @return Map with 'html' key containing rendered HTML
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
            
            logger.debug("Rendering markdown of length: {}", markdown.length());
            
            String html = markdownService.render(markdown);
            
            return ResponseEntity.ok(Map.of(
                "html", html,
                "source", "server",
                "cached", true  // Will be true if it was cached
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
     * Renders markdown for preview without caching.
     * Useful for real-time preview while typing.
     * 
     * @param request Map containing 'content' key with markdown text
     * @return Map with 'html' key containing rendered HTML
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
            
            String html = markdownService.renderPreview(markdown);
            
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
     * Get cache statistics for monitoring.
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        try {
            var stats = markdownService.getCacheStats();
            
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
     * Clear the markdown render cache.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, String>> clearCache() {
        try {
            markdownService.clearCache();
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
}