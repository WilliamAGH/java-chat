package com.williamcallahan.javachat.web;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Base controller class providing common error handling patterns.
 * This eliminates duplicate error handling code found across controllers.
 */
public abstract class BaseController {

    protected final ExceptionResponseBuilder exceptionBuilder;

    /**
     * Creates a base controller wired to the shared exception response builder.
     */
    protected BaseController(ExceptionResponseBuilder exceptionBuilder) {
        this.exceptionBuilder = exceptionBuilder;
    }

    /**
     * Handles service exceptions with standardized error responses.
     *
     * @param e The exception that occurred
     * @param operation Description of the operation that failed
     * @return Standardized error response
     */
    protected ResponseEntity<Map<String, Object>> handleServiceException(
            Exception e, String operation) {
        return exceptionBuilder.buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Failed to " + operation + ": " + e.getMessage(),
            e
        );
    }

    /**
     * Handles validation exceptions with bad request responses.
     *
     * @param e The validation exception
     * @return Bad request error response
     */
    protected ResponseEntity<Map<String, Object>> handleValidationException(
            IllegalArgumentException e) {
        return exceptionBuilder.buildErrorResponse(
            HttpStatus.BAD_REQUEST,
            e.getMessage()
        );
    }

    /**
     * Creates a standardized success response.
     *
     * @param message Success message
     * @return Success response
     */
    protected ResponseEntity<Map<String, Object>> createSuccessResponse(String message) {
        return exceptionBuilder.buildSuccessResponse(message);
    }

    /**
     * Creates a standardized success response with data.
     *
     * @param data Additional response data
     * @return Success response with data
     */
    protected ResponseEntity<Map<String, Object>> createSuccessResponse(Map<String, Object> data) {
        return exceptionBuilder.buildSuccessResponse(data);
    }
}
