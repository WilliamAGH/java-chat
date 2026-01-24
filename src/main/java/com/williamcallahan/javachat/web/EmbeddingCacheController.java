package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.EmbeddingCacheService;
import jakarta.annotation.security.PermitAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST API for managing embedding cache operations.
 */
@RestController
@RequestMapping("/api/embeddings-cache")
@PermitAll
public class EmbeddingCacheController {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheController.class);

    private final EmbeddingCacheService embeddingCacheService;

    /**
     * Creates the embedding cache controller backed by the embedding cache service.
     */
    public EmbeddingCacheController(EmbeddingCacheService embeddingCacheService) {
        this.embeddingCacheService = embeddingCacheService;
    }

    /**
     * Returns cache statistics.
     *
     * @return cache statistics including hit rate and pending uploads
     */
    @GetMapping("/stats")
    public ResponseEntity<EmbeddingCacheService.CacheStats> getCacheStats() {
        return ResponseEntity.ok(embeddingCacheService.getCacheStats());
    }

    /**
     * Saves a timestamped cache snapshot.
     *
     * @return response with operation status and updated stats
     */
    @PostMapping("/snapshot")
    public ResponseEntity<SnapshotResponse> saveSnapshot() {
        try {
            embeddingCacheService.saveSnapshot();
            return ResponseEntity.ok(new SnapshotResponse(
                true,
                "Snapshot saved successfully",
                embeddingCacheService.getCacheStats()
            ));
        } catch (IOException snapshotFailure) {
            log.error("Failed to save snapshot", snapshotFailure);
            return ResponseEntity.internalServerError().body(new SnapshotResponse(
                false,
                snapshotFailure.getMessage(),
                null
            ));
        }
    }

    /**
     * Uploads pending embeddings to the vector store.
     *
     * @param batchSize number of embeddings per batch (default: 100)
     * @return response with upload count and updated stats
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadToVectorStore(
            @RequestParam(defaultValue = "100") int batchSize) {

        int uploaded = embeddingCacheService.uploadPendingToVectorStore(batchSize);
        return ResponseEntity.ok(new UploadResponse(
            true,
            uploaded,
            embeddingCacheService.getCacheStats()
        ));
    }

    /**
     * Exports cache to a file.
     *
     * @param filename optional custom filename (creates snapshot if not provided)
     * @return response with operation status and updated stats
     */
    @PostMapping("/export")
    public ResponseEntity<ExportResponse> exportCache(
            @RequestParam(required = false) String filename) {

        try {
            if (filename == null || filename.isEmpty()) {
                embeddingCacheService.saveSnapshot();
            } else {
                embeddingCacheService.exportCache(filename);
            }
            return ResponseEntity.ok(new ExportResponse(
                true,
                "Cache exported successfully",
                filename,
                embeddingCacheService.getCacheStats()
            ));
        } catch (IOException exportFailure) {
            log.error("Failed to export cache", exportFailure);
            return ResponseEntity.internalServerError().body(new ExportResponse(
                false,
                exportFailure.getMessage(),
                filename,
                null
            ));
        }
    }

    /**
     * Imports embeddings from a file.
     *
     * @param filename name of file to import
     * @return response with operation status and updated stats
     */
    @PostMapping("/import")
    public ResponseEntity<ImportResponse> importCache(
            @RequestParam String filename) {

        try {
            embeddingCacheService.importCache(filename);
            return ResponseEntity.ok(new ImportResponse(
                true,
                "Cache imported successfully",
                filename,
                embeddingCacheService.getCacheStats()
            ));
        } catch (Exception importFailure) {
            log.error("Failed to import cache", importFailure);
            return ResponseEntity.internalServerError().body(new ImportResponse(
                false,
                importFailure.getMessage(),
                filename,
                null
            ));
        }
    }

    /**
     * Response for snapshot operations.
     */
    public record SnapshotResponse(
        boolean success,
        String message,
        EmbeddingCacheService.CacheStats stats
    ) {}

    /**
     * Response for upload operations.
     */
    public record UploadResponse(
        boolean success,
        int uploaded,
        EmbeddingCacheService.CacheStats stats
    ) {}

    /**
     * Response for export operations.
     */
    public record ExportResponse(
        boolean success,
        String message,
        String filename,
        EmbeddingCacheService.CacheStats stats
    ) {}

    /**
     * Response for import operations.
     */
    public record ImportResponse(
        boolean success,
        String message,
        String filename,
        EmbeddingCacheService.CacheStats stats
    ) {}
}
