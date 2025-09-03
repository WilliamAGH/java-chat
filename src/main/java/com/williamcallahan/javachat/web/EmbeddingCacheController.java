package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.EmbeddingCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API for managing embedding cache operations
 */
@RestController
@RequestMapping("/api/embeddings-cache")
public class EmbeddingCacheController {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheController.class);
    
    private final EmbeddingCacheService embeddingCacheService;
    
    public EmbeddingCacheController(EmbeddingCacheService embeddingCacheService) {
        this.embeddingCacheService = embeddingCacheService;
    }
    
    /**
     * GET /api/embeddings-cache/stats - Returns cache statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        return ResponseEntity.ok(embeddingCacheService.getCacheStats());
    }
    
    /**
     * POST /api/embeddings-cache/snapshot - Saves timestamped cache snapshot
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> saveSnapshot() {
        try {
            embeddingCacheService.saveSnapshot();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Snapshot saved successfully");
            response.put("stats", embeddingCacheService.getCacheStats());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Failed to save snapshot", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
    
    /**
     * POST /api/embeddings-cache/upload - Uploads pending embeddings to Qdrant
     * @param batchSize Number of embeddings per batch (default: 100)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadToVectorStore(
            @RequestParam(defaultValue = "100") int batchSize) {
        
        int uploaded = embeddingCacheService.uploadPendingToVectorStore(batchSize);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("uploaded", uploaded);
        response.put("stats", embeddingCacheService.getCacheStats());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * POST /api/embeddings-cache/export - Exports cache to file
     * @param filename Optional custom filename (creates snapshot if not provided)
     */
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportCache(
            @RequestParam(required = false) String filename) {
        
        try {
            if (filename == null || filename.isEmpty()) {
                embeddingCacheService.saveSnapshot();
            } else {
                embeddingCacheService.exportCache(filename);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cache exported successfully");
            response.put("filename", filename);
            response.put("stats", embeddingCacheService.getCacheStats());
            return ResponseEntity.ok(response);
            
        } catch (IOException e) {
            log.error("Failed to export cache", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
    
    /**
     * POST /api/embeddings-cache/import - Imports embeddings from file
     * @param filename Name of file to import
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importCache(
            @RequestParam String filename) {
        
        try {
            embeddingCacheService.importCache(filename);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cache imported successfully");
            response.put("filename", filename);
            response.put("stats", embeddingCacheService.getCacheStats());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to import cache", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}