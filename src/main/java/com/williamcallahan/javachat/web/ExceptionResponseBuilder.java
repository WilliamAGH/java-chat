package com.williamcallahan.javachat.web;

import com.openai.errors.OpenAIServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;

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
            .body(ApiErrorResponse.error(message, describeException(exception)));
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

    /**
     * Builds a detailed error description suitable for API responses or UI diagnostics.
     *
     * @param exception the exception to describe
     * @return detailed description, or null when no exception is provided
     */
    public String describeException(Exception exception) {
        if (exception == null) {
            return null;
        }
        StringBuilder details = new StringBuilder();
        details.append(exception.getClass().getSimpleName());
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            details.append(": ").append(message);
        }

        if (exception instanceof RestClientResponseException restClientException) {
            appendRestClientDetails(details, restClientException);
        }
        if (exception instanceof WebClientResponseException webClientException) {
            appendWebClientDetails(details, webClientException);
        }
        if (exception instanceof OpenAIServiceException openAiException) {
            appendOpenAiDetails(details, openAiException);
        }
        if (exception instanceof ResponseStatusException statusException) {
            appendStatusExceptionDetails(details, statusException);
        }
        return details.toString();
    }

    private void appendRestClientDetails(StringBuilder details, RestClientResponseException exception) {
        details.append(" [httpStatus=").append(exception.getStatusCode().value());
        String statusText = exception.getStatusText();
        if (!statusText.isBlank()) {
            details.append(" ").append(statusText);
        }
        String responseBody = exception.getResponseBodyAsString();
        if (!responseBody.isBlank()) {
            details.append(", body=").append(responseBody);
        }
        HttpHeaders headers = Optional.ofNullable(exception.getResponseHeaders())
            .orElseGet(HttpHeaders::new);
        if (!headers.isEmpty()) {
            details.append(", headers=").append(headers);
        }
        details.append("]");
    }

    private void appendWebClientDetails(StringBuilder details, WebClientResponseException exception) {
        details.append(" [httpStatus=").append(exception.getStatusCode().value());
        String statusText = exception.getStatusText();
        if (!statusText.isBlank()) {
            details.append(" ").append(statusText);
        }
        String responseBody = exception.getResponseBodyAsString();
        if (!responseBody.isBlank()) {
            details.append(", body=").append(responseBody);
        }
        HttpHeaders headers = exception.getHeaders();
        if (!headers.isEmpty()) {
            details.append(", headers=").append(headers);
        }
        details.append("]");
    }

    private void appendOpenAiDetails(StringBuilder details, OpenAIServiceException exception) {
        details.append(" [httpStatus=").append(exception.statusCode());
        var headers = exception.headers();
        if (!headers.isEmpty()) {
            details.append(", headers=").append(headers);
        }
        var bodyJson = exception.body();
        String body = bodyJson.toString();
        if (!body.isBlank()) {
            details.append(", body=").append(body);
        }
        exception.code().ifPresent(code -> details.append(", code=").append(code));
        exception.param().ifPresent(param -> details.append(", param=").append(param));
        exception.type().ifPresent(type -> details.append(", type=").append(type));
        details.append("]");
    }

    private void appendStatusExceptionDetails(StringBuilder details, ResponseStatusException exception) {
        details.append(" [httpStatus=").append(exception.getStatusCode().value()).append("]");
    }
}
