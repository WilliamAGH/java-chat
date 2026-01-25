package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Custom error controller that provides beautiful error pages for the Java Chat application.
 * Handles both HTML page requests and API JSON error responses.
 */
@Controller
@PermitAll
@PreAuthorize("permitAll()")
public class CustomErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(CustomErrorController.class);
    private static final String ERROR_PATH = "/error";
    private static final String ERROR_VIEW_ACCESS_DENIED = "forward:/errors/access-denied";
    private static final String ERROR_VIEW_AUTH_REQUIRED = "forward:/errors/authentication-required";
    private static final String ERROR_VIEW_INTERNAL = "forward:/errors/internal-error";
    private static final String ERROR_VIEW_INVALID_ARGUMENT = "forward:/errors/invalid-argument";
    private static final String ERROR_VIEW_METHOD_NOT_ALLOWED = "forward:/errors/method-not-allowed";
    private static final String ERROR_VIEW_NOT_ACCEPTABLE = "forward:/errors/not-acceptable";
    private static final String ERROR_VIEW_NOT_FOUND = "forward:/errors/not-found";
    private static final String ERROR_VIEW_NOT_IMPLEMENTED = "forward:/errors/not-implemented";
    private static final String ERROR_VIEW_RATE_LIMITED = "forward:/errors/rate-limited";
    private static final String ERROR_VIEW_SERVICE_UNAVAILABLE = "forward:/errors/service-unavailable";
    private static final String ERROR_VIEW_UNSUPPORTED_MEDIA = "forward:/errors/unsupported-media-type";

    private final ExceptionResponseBuilder exceptionBuilder;

    /**
     * Creates the error controller backed by the shared exception response builder.
     *
     * @param exceptionBuilder standardized error response builder
     */
    public CustomErrorController(ExceptionResponseBuilder exceptionBuilder) {
        this.exceptionBuilder = exceptionBuilder;
    }

    /**
     * Handles error requests and returns appropriate error pages or JSON responses.
     *
     * @param request The HTTP request
     * @param model Spring MVC model for template rendering
     * @return ModelAndView for HTML requests or ResponseEntity for API requests
     */
    // Explicitly specify all HTTP methods - error handlers must respond to any request type
    // that might generate an error. This is intentional, not a CSRF risk.
    @RequestMapping(
            value = ERROR_PATH,
            method = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.PATCH,
                RequestMethod.HEAD,
                RequestMethod.OPTIONS
            })
    public Object handleError(HttpServletRequest request, Model model) {
        // Get error details from request attributes
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Object requestUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        int statusCode = status != null ? (Integer) status : 500;
        String errorMessage = message != null ? message.toString() : "An unexpected error occurred";
        String uri = requestUri != null ? requestUri.toString() : request.getRequestURI();

        // Log the error for monitoring without echoing request-derived strings
        log.error("Error {} occurred while handling request", statusCode);
        if (exception instanceof Exception exceptionInstance) {
            log.error("Exception type: {}", exceptionInstance.getClass().getSimpleName());
        }

        // Determine if this is an API request or a page request
        boolean isApiRequest = uri.startsWith("/api/");

        if (isApiRequest) {
            // Return JSON error response for API requests
            return handleApiError(statusCode, errorMessage, (Exception) exception);
        } else {
            // Return HTML error page for browser requests
            return handlePageError(statusCode, resolveUserFacingMessage(statusCode), uri, model);
        }
    }

    /**
     * Handles API error responses with JSON format.
     */
    private ResponseEntity<ApiErrorResponse> handleApiError(int statusCode, String message, Exception exception) {
        HttpStatus httpStatus = HttpStatus.resolve(statusCode);
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (exception != null) {
            return exceptionBuilder.buildErrorResponse(httpStatus, message, exception);
        } else {
            return exceptionBuilder.buildErrorResponse(httpStatus, message);
        }
    }

    /**
     * Handles page error responses with HTML error pages.
     */
    private ModelAndView handlePageError(int statusCode, String message, String uri, Model model) {
        ModelAndView modelAndView = new ModelAndView();

        // Add error details to model for potential template use
        model.addAttribute("status", statusCode);
        model.addAttribute("error", HttpStatus.resolve(statusCode));
        model.addAttribute("message", message);
        model.addAttribute("path", uri);
        model.addAttribute("timestamp", System.currentTimeMillis());

        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode);
        if (resolvedStatus == null) {
            resolvedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        modelAndView.setViewName(resolveErrorViewName(resolvedStatus));

        return modelAndView;
    }

    private String resolveUserFacingMessage(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status != null) {
            return status.getReasonPhrase();
        }
        return "Unexpected error";
    }

    private String resolveErrorViewName(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ERROR_VIEW_NOT_FOUND;
            case BAD_REQUEST -> ERROR_VIEW_INVALID_ARGUMENT;
            case UNAUTHORIZED -> ERROR_VIEW_AUTH_REQUIRED;
            case FORBIDDEN -> ERROR_VIEW_ACCESS_DENIED;
            case METHOD_NOT_ALLOWED -> ERROR_VIEW_METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ERROR_VIEW_NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> ERROR_VIEW_UNSUPPORTED_MEDIA;
            case TOO_MANY_REQUESTS -> ERROR_VIEW_RATE_LIMITED;
            case NOT_IMPLEMENTED -> ERROR_VIEW_NOT_IMPLEMENTED;
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> ERROR_VIEW_SERVICE_UNAVAILABLE;
            default -> ERROR_VIEW_INTERNAL;
        };
    }

    /**
     * Returns the error path for Spring Boot's ErrorController interface.
     */
    public String getErrorPath() {
        return ERROR_PATH;
    }
}
