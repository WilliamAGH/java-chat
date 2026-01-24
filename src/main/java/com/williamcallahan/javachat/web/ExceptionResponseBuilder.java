package com.williamcallahan.javachat.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Centralized utility for building consistent error responses across controllers.
 * This eliminates duplication of error response patterns found in IngestionController and ChatController.
 */
@Component
public class ExceptionResponseBuilder {

    /**
     * Builds a standardized error response with status and message.
     *
     * @param status The HTTP status code
     * @param message The error message
     * @return ResponseEntity with error details
     */
    public ResponseEntity<ApiErrorResponse> buildErrorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .body(ApiErrorResponse.error(message));
    }

    /**
     * Builds a standardized error response with status, message, and exception details.
     *
     * @param status The HTTP status code
     * @param message The error message
     * @param exception The exception that occurred
     * @return ResponseEntity with error details
     */
    public ResponseEntity<ApiErrorResponse> buildErrorResponse(HttpStatus status, String message, Exception exception) {
        return ResponseEntity.status(status)
            .body(ApiErrorResponse.error(message, exception == null ? null : exception.getMessage()));
    }

    /**
     * Builds a standardized success response with a simple message.
     *
     * @param message The success message
     * @return ResponseEntity with success details
     */
    public ResponseEntity<ApiSuccessResponse> buildSuccessResponse(String message) {
        return ResponseEntity.ok(ApiSuccessResponse.success(message));
    }
}
