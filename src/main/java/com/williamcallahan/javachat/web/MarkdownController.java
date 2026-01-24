package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.markdown.MarkdownCacheClearOutcome;
import com.williamcallahan.javachat.domain.markdown.MarkdownCacheClearResponse;
import com.williamcallahan.javachat.domain.markdown.MarkdownCacheStatsSnapshot;
import com.williamcallahan.javachat.domain.markdown.MarkdownCacheStatsResponse;
import com.williamcallahan.javachat.domain.markdown.MarkdownErrorResponse;
import com.williamcallahan.javachat.domain.markdown.MarkdownRenderOutcome;
import com.williamcallahan.javachat.domain.markdown.MarkdownRenderResponse;
import com.williamcallahan.javachat.domain.markdown.MarkdownRenderRequest;
import com.williamcallahan.javachat.domain.markdown.MarkdownStructuredErrorResponse;
import com.williamcallahan.javachat.domain.markdown.MarkdownStructuredOutcome;
import com.williamcallahan.javachat.domain.markdown.MarkdownStructuredResponse;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import jakarta.annotation.security.PermitAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for markdown rendering operations.
 * Provides endpoints for converting markdown to HTML with proper formatting.
 */
@RestController
@RequestMapping("/api/markdown")
@PermitAll
@PreAuthorize("permitAll()")
public class MarkdownController {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownController.class);
    
    private final MarkdownService markdownService;
    private final UnifiedMarkdownService unifiedMarkdownService;
    private final ExceptionResponseBuilder exceptionBuilder;
    
    /**
     * Creates a markdown controller with required services.
     *
     * @param markdownService legacy markdown processing service
     * @param unifiedMarkdownService AST-based unified markdown processor
     * @param exceptionBuilder shared exception response builder
     */
    public MarkdownController(MarkdownService markdownService,
                              UnifiedMarkdownService unifiedMarkdownService,
                              ExceptionResponseBuilder exceptionBuilder) {
        this.markdownService = markdownService;
        this.unifiedMarkdownService = unifiedMarkdownService;
        this.exceptionBuilder = exceptionBuilder;
    }
    
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
     * @return A {@link ResponseEntity} containing the rendered markdown outcome.
     */
    @PostMapping(value = "/render", 
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MarkdownRenderResponse> renderMarkdown(@RequestBody MarkdownRenderRequest renderRequest) {
        try {
            if (renderRequest.isBlank()) {
                return ResponseEntity.ok(new MarkdownRenderOutcome(
                    "",
                    "server",
                    false,
                    0,
                    0
                ));
            }
            
            logger.debug("Processing markdown of length: {}", renderRequest.content().length());
            
            var processed = markdownService.processStructured(renderRequest.content());
            
            return ResponseEntity.ok(new MarkdownRenderOutcome(
                processed.html(),
                "server",
                false,
                processed.citations().size(),
                processed.enrichments().size()
            ));
            
        } catch (RuntimeException renderException) {
            logger.error("Error rendering markdown (exception type: {})",
                renderException.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new MarkdownErrorResponse(
                "Failed to render markdown",
                exceptionBuilder.describeException(renderException)
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
     * @return A {@link ResponseEntity} containing the rendered markdown outcome.
     */
    @PostMapping(value = "/preview",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MarkdownRenderResponse> previewMarkdown(@RequestBody MarkdownRenderRequest renderRequest) {
        try {
            if (renderRequest.isBlank()) {
                return ResponseEntity.ok(new MarkdownRenderOutcome(
                    "",
                    "preview",
                    false,
                    0,
                    0
                ));
            }
            
            var processed = markdownService.processStructured(renderRequest.content());
            
            return ResponseEntity.ok(new MarkdownRenderOutcome(
                processed.html(),
                "preview",
                false,
                processed.citations().size(),
                processed.enrichments().size()
            ));
            
        } catch (RuntimeException previewException) {
            logger.error("Error rendering preview markdown (exception type: {})",
                previewException.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new MarkdownErrorResponse(
                "Failed to render preview",
                exceptionBuilder.describeException(previewException)
            ));
        }
    }
    
    /**
     * Retrieves statistics about the server-side markdown render cache.
     * Provides metrics like hit count, miss count, size, and hit rate.
     *
     * @return A {@link ResponseEntity} with cache statistics.
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<MarkdownCacheStatsResponse> getCacheStats() {
        try {
            var stats = unifiedMarkdownService.getCacheStats();
            
            return ResponseEntity.ok(new MarkdownCacheStatsSnapshot(
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount(),
                stats.size(),
                String.format("%.2f%%", stats.hitRate() * 100)
            ));
            
        } catch (RuntimeException statsException) {
            logger.error("Error getting cache stats (exception type: {})",
                statsException.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new MarkdownErrorResponse(
                "Failed to get cache stats",
                exceptionBuilder.describeException(statsException)
            ));
        }
    }
    
    /**
     * Clears the server-side markdown render cache.
     *
     * @return A {@link ResponseEntity} with a status message.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<MarkdownCacheClearResponse> clearCache() {
        try {
            unifiedMarkdownService.clearCache();
            logger.info("Markdown cache cleared via API");
            
            return ResponseEntity.ok(new MarkdownCacheClearOutcome(
                "success",
                "Cache cleared successfully"
            ));
            
        } catch (RuntimeException clearException) {
            logger.error("Error clearing cache (exception type: {})",
                clearException.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new MarkdownCacheClearOutcome(
                "error",
                "Failed to clear cache: " + exceptionBuilder.describeException(clearException)
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
    public ResponseEntity<MarkdownStructuredResponse> renderStructured(@RequestBody MarkdownRenderRequest renderRequest) {
        try {
            if (renderRequest.isBlank()) {
                return ResponseEntity.ok(new MarkdownStructuredOutcome(
                    "",
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    0L,
                    "unified-service",
                    0,
                    true
                ));
            }
            
            logger.debug("Processing markdown with UnifiedMarkdownService, length: {}", renderRequest.content().length());
            
            var processed = unifiedMarkdownService.process(renderRequest.content());
            
            return ResponseEntity.ok(new MarkdownStructuredOutcome(
                processed.html(),
                processed.citations(),
                processed.enrichments(),
                processed.warnings(),
                processed.processingTimeMs(),
                "unified-service",
                processed.getStructuredElementCount(),
                processed.isClean()
            ));
            
        } catch (RuntimeException structuredException) {
            logger.error("Error rendering structured markdown (exception type: {})",
                structuredException.getClass().getSimpleName());
            return ResponseEntity.status(500).body(new MarkdownStructuredErrorResponse(
                "Failed to render structured markdown",
                "unified-service",
                exceptionBuilder.describeException(structuredException)
            ));
        }
    }
}
