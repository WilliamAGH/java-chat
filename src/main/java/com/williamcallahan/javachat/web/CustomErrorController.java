package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * Custom error controller that provides beautiful error pages for the Java Chat application.
 * Handles both HTML page requests and API JSON error responses.
 */
@Controller
@PermitAll
public class CustomErrorController implements ErrorController {
    
    private static final Logger log = LoggerFactory.getLogger(CustomErrorController.class);
    private static final String ERROR_PATH = "/error";
    
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
    @RequestMapping(value = ERROR_PATH, method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
        RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS
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
    private ResponseEntity<Map<String, Object>> handleApiError(int statusCode, String message, Exception exception) {
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
        
        // Route to appropriate error page based on status code
        switch (statusCode) {
            case 404:
                // Redirect to our beautiful 404 page
                modelAndView.setViewName("forward:/404.html");
                break;
            case 400:
            case 401:
            case 403:
            case 500:
            case 502:
            case 503:
            default:
                // Redirect to a static error page; status stays in the model for rendering.
                modelAndView.setViewName("forward:/error.html");
                break;
        }
        
        return modelAndView;
    }

    private String resolveUserFacingMessage(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status != null) {
            return status.getReasonPhrase();
        }
        return "Unexpected error";
    }
    
    /**
     * Returns the error path for Spring Boot's ErrorController interface.
     */
    public String getErrorPath() {
        return ERROR_PATH;
    }
}
