package com.williamcallahan.javachat.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
            .body(Map.of("status", "error", "message", message));
    }

    /**
     * Builds a standardized error response with status, message, and exception details.
     *
     * @param status The HTTP status code
     * @param message The error message
     * @param exception The exception that occurred
     * @return ResponseEntity with error details
     */
    public ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message, Exception exception) {
        return ResponseEntity.status(status)
            .body(Map.of(
                "status", "error",
                "message", message,
                "details", exception.getMessage()
            ));
    }

    /**
     * Builds a standardized success response.
     *
     * @param data Additional data to include in the response
     * @return ResponseEntity with success details
     */
    public ResponseEntity<Map<String, Object>> buildSuccessResponse(Map<String, Object> data) {
        var response = new java.util.HashMap<String, Object>();
        response.put("status", "success");
        response.putAll(data);
        return ResponseEntity.ok(response);
    }

    /**
     * Builds a standardized success response with a simple message.
     *
     * @param message The success message
     * @return ResponseEntity with success details
     */
    public ResponseEntity<Map<String, Object>> buildSuccessResponse(String message) {
        return ResponseEntity.ok(Map.of("status", "success", "message", message));
    }
}