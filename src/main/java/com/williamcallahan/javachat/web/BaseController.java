package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.errors.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    protected ResponseEntity<ApiResponse> handleServiceException(Exception e, String operation) {
        return exceptionBuilder.buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR, "Failed to " + operation + ": " + e.getMessage(), e);
    }

    /**
     * Handles validation exceptions with bad request responses.
     *
     * @param validationException The validation exception
     * @return Bad request error response
     */
    protected ResponseEntity<ApiResponse> handleValidationException(IllegalArgumentException validationException) {
        return exceptionBuilder.buildErrorResponse(HttpStatus.BAD_REQUEST, validationException.getMessage());
    }

    /**
     * Creates a standardized success response.
     *
     * @param message Success message
     * @return Success response
     */
    protected ResponseEntity<ApiResponse> createSuccessResponse(String message) {
        return exceptionBuilder.buildSuccessResponse(message);
    }

    /**
     * Describes an exception with HTTP context when available for UI diagnostics.
     *
     * @param exception exception to describe
     * @return formatted exception details or null when no exception is provided
     */
    protected String describeException(Exception exception) {
        return exceptionBuilder.describeException(exception);
    }
}
