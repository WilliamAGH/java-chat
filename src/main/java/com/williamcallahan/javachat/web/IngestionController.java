package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.DocsIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);
    private static final int MAX_ALLOWED_PAGES = 10000;

    private final DocsIngestionService docsIngestionService;

    public IngestionController(DocsIngestionService docsIngestionService, ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.docsIngestionService = docsIngestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestParam(name = "maxPages", defaultValue = "100") 
            @Min(value = 1, message = "maxPages must be at least 1")
            @Max(value = MAX_ALLOWED_PAGES, message = "maxPages cannot exceed " + MAX_ALLOWED_PAGES)
            int maxPages) {
        
        try {
            log.info("Starting ingestion for up to {} pages", maxPages);
            docsIngestionService.crawlAndIngest(maxPages);

            return createSuccessResponse(String.format("Ingestion completed for up to %d pages", maxPages));

        } catch (IOException e) {
            log.error("IO error during ingestion: {}", e.getMessage(), e);
            return handleServiceException(e, "ingest documents");
        } catch (Exception e) {
            log.error("Unexpected error during ingestion: {}", e.getMessage(), e);
            return handleServiceException(e, "perform ingestion");
        }
    }
    
    @PostMapping("/local")
    public ResponseEntity<Map<String, Object>> ingestLocal(
            @RequestParam(name = "dir", defaultValue = "data/docs") String dir,
            @RequestParam(name = "maxFiles", defaultValue = "50000")
            @Min(1) @Max(1000000) int maxFiles) {
        try {
            int processed = docsIngestionService.ingestLocalDirectory(dir, maxFiles);
            return createSuccessResponse(Map.of(
                    "processed", processed,
                    "dir", dir
            ));
        } catch (IllegalArgumentException e) {
            return handleValidationException(e);
        } catch (Exception e) {
            log.error("Local ingestion error: {}", e.getMessage(), e);
            return handleServiceException(e, "perform local ingestion");
        }
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(IllegalArgumentException e) {
        return super.handleValidationException(e);
    }
}





