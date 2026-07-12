package com.williamcallahan.javachat.web;

import com.openai.errors.OpenAIServiceException;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import com.williamcallahan.javachat.domain.errors.ApiResponse;
import com.williamcallahan.javachat.domain.errors.ApiSuccessResponse;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralized utility for building consistent error responses across controllers.
 * This eliminates duplication of error response patterns found in IngestionController and ChatController.
 */
@Component
public class ExceptionResponseBuilder {
    private static final String EXCEPTION_REQUIRED_MESSAGE = "exception must not be null";

    /**
     * Builds a standardized error response with status and message.
     *
     * @param status The HTTP status code
     * @param message The error message
     * @return ResponseEntity with error details
     */
    public ResponseEntity<ApiResponse> buildErrorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.error(message));
    }

    /**
     * Builds a standardized error response with status, message, and exception details.
     *
     * @param status The HTTP status code
     * @param message The error message
     * @param exception The exception that occurred
     * @return ResponseEntity with error details
     */
    public ResponseEntity<ApiResponse> buildErrorResponse(HttpStatus status, String message, Exception exception) {
        return ResponseEntity.status(status).body(ApiErrorResponse.error(message, describeException(exception)));
    }

    /**
     * Builds a standardized success response with a simple message.
     *
     * @param message The success message
     * @return ResponseEntity with success details
     */
    public ResponseEntity<ApiResponse> buildSuccessResponse(String message) {
        return ResponseEntity.ok(ApiSuccessResponse.success(message));
    }

    /**
     * Builds a client-safe error description without downstream messages, headers, or bodies.
     *
     * @param exception the exception to describe
     * @return exception type and numeric HTTP status when available
     * @throws NullPointerException when exception is null
     */
    public String describeException(Exception exception) {
        Objects.requireNonNull(exception, EXCEPTION_REQUIRED_MESSAGE);
        StringBuilder details = new StringBuilder(exception.getClass().getSimpleName());

        if (exception instanceof RestClientResponseException restClientException) {
            appendHttpStatus(details, restClientException.getStatusCode().value());
        } else if (exception instanceof WebClientResponseException webClientException) {
            appendHttpStatus(details, webClientException.getStatusCode().value());
        } else if (exception instanceof OpenAIServiceException openAiException) {
            appendHttpStatus(details, openAiException.statusCode());
        } else if (exception instanceof ResponseStatusException statusException) {
            appendHttpStatus(details, statusException.getStatusCode().value());
        }
        return details.toString();
    }

    private static void appendHttpStatus(StringBuilder details, int httpStatus) {
        details.append(" [httpStatus=").append(httpStatus).append("]");
    }
}
