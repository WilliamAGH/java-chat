package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.errors.ApiResponse;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private static final int MAX_LOG_FIELD_LENGTH = 512;
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
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
    public Object handleError(HttpServletRequest request, HttpServletResponse servletResponse, Model model) {
        // Get error details from request attributes
        Object errorStatusCodeAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object errorExceptionAttribute = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Object errorRequestUriAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        int statusCode = errorStatusCodeAttribute instanceof Integer errorStatusCode
                ? errorStatusCode
                : HttpStatus.INTERNAL_SERVER_ERROR.value();
        String requestUri =
                errorRequestUriAttribute != null ? errorRequestUriAttribute.toString() : request.getRequestURI();
        String requestId = request.getRequestId();
        servletResponse.setHeader(REQUEST_ID_HEADER, requestId);

        // Determine if this is an API request or a page request
        boolean isApiRequest = requestUri.equals("/api") || requestUri.startsWith("/api/");
        logRequestFailure(request, statusCode, requestUri, isApiRequest, requestId, errorExceptionAttribute);

        if (isApiRequest) {
            // Return JSON error response for API requests
            Exception requestException =
                    errorExceptionAttribute instanceof Exception exceptionInstance ? exceptionInstance : null;
            return handleApiError(statusCode, requestException);
        } else {
            // Return HTML error page for browser requests
            return handlePageError(statusCode, resolveUserFacingMessage(statusCode), requestUri, model);
        }
    }

    private void logRequestFailure(
            HttpServletRequest request,
            int statusCode,
            String uri,
            boolean apiRequest,
            String requestId,
            Object exception) {
        String method = safeLogField(request.getMethod());
        String canonicalUri = safeLogField(uri.split("[?#]", 2)[0]);
        String serverHost = safeLogField(request.getServerName());
        String userAgent = safeLogField(request.getHeader("User-Agent"));
        String safeRequestId = safeLogField(requestId);
        String source = safeLogField(request.getAttribute(RequestDispatcher.ERROR_SERVLET_NAME));
        String diagnostic = "Request failed status={} source={} method={} uri={} host={} userAgent={} requestId={}";

        if (statusCode >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            if (exception instanceof Exception exceptionInstance) {
                log.error(
                        diagnostic,
                        statusCode,
                        source,
                        method,
                        canonicalUri,
                        serverHost,
                        userAgent,
                        safeRequestId,
                        exceptionInstance);
            } else {
                log.error(diagnostic, statusCode, source, method, canonicalUri, serverHost, userAgent, safeRequestId);
            }
        } else if (statusCode == HttpStatus.NOT_FOUND.value() && apiRequest) {
            log.warn(diagnostic, statusCode, source, method, canonicalUri, serverHost, userAgent, safeRequestId);
        } else {
            log.info(diagnostic, statusCode, source, method, canonicalUri, serverHost, userAgent, safeRequestId);
        }
    }

    private String safeLogField(Object requestField) {
        if (requestField == null) {
            return "unknown";
        }
        String logField = requestField.toString();
        int boundedLength = Math.min(logField.length(), MAX_LOG_FIELD_LENGTH);
        StringBuilder safeField = new StringBuilder(boundedLength);
        for (int index = 0; index < boundedLength; index++) {
            char character = logField.charAt(index);
            safeField.append(Character.isISOControl(character) ? '?' : character);
        }
        return safeField.toString();
    }

    /**
     * Handles API error responses with JSON format.
     */
    private ResponseEntity<ApiResponse> handleApiError(int statusCode, Exception requestException) {
        HttpStatus httpStatus = resolveHttpStatus(statusCode);
        String userFacingMessage = httpStatus.getReasonPhrase();

        if (requestException != null) {
            return exceptionBuilder.buildErrorResponse(httpStatus, userFacingMessage, requestException);
        } else {
            return exceptionBuilder.buildErrorResponse(httpStatus, userFacingMessage);
        }
    }

    /**
     * Handles page error responses with HTML error pages.
     */
    private ModelAndView handlePageError(int statusCode, String userFacingMessage, String requestUri, Model model) {
        ModelAndView modelAndView = new ModelAndView();

        // Add error details to model for potential template use
        model.addAttribute("status", statusCode);
        model.addAttribute("error", HttpStatus.resolve(statusCode));
        model.addAttribute("message", userFacingMessage);
        model.addAttribute("path", requestUri);
        model.addAttribute("timestamp", System.currentTimeMillis());

        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode);
        if (resolvedStatus == null) {
            resolvedStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        modelAndView.setViewName(resolveErrorViewName(resolvedStatus));
        // Carry the real status onto the forwarded error view: without this the
        // forward renders with 200, so a missing hashed asset returns 200 text/html
        // and browsers raise a strict-MIME module error instead of a clean 404.
        modelAndView.setStatus(resolvedStatus);

        return modelAndView;
    }

    private String resolveUserFacingMessage(int statusCode) {
        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode);
        if (resolvedStatus != null) {
            return resolvedStatus.getReasonPhrase();
        }
        return "Unexpected error";
    }

    private HttpStatus resolveHttpStatus(int statusCode) {
        HttpStatus resolvedStatus = HttpStatus.resolve(statusCode);
        if (resolvedStatus != null) {
            return resolvedStatus;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
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
