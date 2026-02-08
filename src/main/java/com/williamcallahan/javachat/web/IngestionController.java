package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.ingestion.IngestionErrorResponse;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalResponse;
import com.williamcallahan.javachat.domain.ingestion.IngestionResponse;
import com.williamcallahan.javachat.domain.ingestion.IngestionRunOutcome;
import com.williamcallahan.javachat.service.DocsIngestionService;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes ingestion endpoints for crawling remote docs and ingesting local directories.
 */
@RestController
@RequestMapping("/api/ingest")
@PermitAll
@PreAuthorize("permitAll()")
public class IngestionController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);
    private static final int MAX_ALLOWED_PAGES = 10000;

    private final DocsIngestionService docsIngestionService;

    /**
     * Creates the ingestion controller backed by the ingestion service and shared error response builder.
     */
    public IngestionController(DocsIngestionService docsIngestionService, ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.docsIngestionService = docsIngestionService;
    }

    /**
     * Starts an ingestion run that crawls and ingests up to the requested number of pages.
     */
    @PostMapping
    public ResponseEntity<? extends IngestionResponse> ingest(
            @RequestParam(name = "maxPages", defaultValue = "100")
                    @Min(value = 1, message = "maxPages must be at least 1")
                    @Max(value = MAX_ALLOWED_PAGES, message = "maxPages cannot exceed " + MAX_ALLOWED_PAGES)
                    int maxPages) {

        try {
            log.info("Starting ingestion for up to {} pages", maxPages);
            docsIngestionService.crawlAndIngest(maxPages);

            return ResponseEntity.ok(
                    IngestionRunOutcome.success(String.format("Ingestion completed for up to %d pages", maxPages)));

        } catch (IOException ioException) {
            log.error(
                    "IO error during ingestion (exception type: {})",
                    ioException.getClass().getSimpleName());
            return buildIngestionError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to ingest documents: " + ioException.getMessage(),
                    ioException);
        } catch (RuntimeException runtimeException) {
            log.error(
                    "Unexpected error during ingestion (exception type: {})",
                    runtimeException.getClass().getSimpleName());
            return buildIngestionError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to perform ingestion: " + runtimeException.getMessage(),
                    runtimeException);
        }
    }

    /**
     * Ingests documents from a local directory, primarily for offline or development workflows.
     */
    @PostMapping("/local")
    public ResponseEntity<? extends IngestionLocalResponse> ingestLocal(
            @RequestParam(name = "dir", defaultValue = "data/docs") String directory,
            @RequestParam(name = "maxFiles", defaultValue = "50000") @Min(1) @Max(1000000) int maxFiles) {
        try {
            IngestionLocalOutcome outcome = docsIngestionService.ingestLocalDirectory(directory, maxFiles);
            return ResponseEntity.ok(outcome);
        } catch (IllegalArgumentException illegalArgumentException) {
            return buildIngestionValidationError(illegalArgumentException);
        } catch (IOException ioException) {
            log.error(
                    "Local ingestion IO error (exception type: {})",
                    ioException.getClass().getSimpleName());
            return buildIngestionError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to perform local ingestion: " + ioException.getMessage(),
                    ioException);
        } catch (RuntimeException runtimeException) {
            log.error(
                    "Local ingestion error (exception type: {})",
                    runtimeException.getClass().getSimpleName());
            return buildIngestionError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to perform local ingestion: " + runtimeException.getMessage(),
                    runtimeException);
        }
    }

    /**
     * Returns a standardized validation error response for invalid ingestion request parameters.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<IngestionErrorResponse> handleIngestionValidationException(
            IllegalArgumentException validationException) {
        return buildIngestionValidationError(validationException);
    }

    private ResponseEntity<IngestionErrorResponse> buildIngestionValidationError(
            IllegalArgumentException validationException) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(IngestionErrorResponse.error(validationException.getMessage()));
    }

    private ResponseEntity<IngestionErrorResponse> buildIngestionError(
            HttpStatus status, String message, Exception exception) {
        String details = describeException(exception);
        return ResponseEntity.status(status).body(IngestionErrorResponse.error(message, details));
    }
}
