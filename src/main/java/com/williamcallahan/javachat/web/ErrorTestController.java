package com.williamcallahan.javachat.web;

import jakarta.annotation.security.PermitAll;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test controller for demonstrating error pages.
 * This controller provides endpoints to test different error scenarios.
 * 
 * IMPORTANT: This controller should be removed or disabled in production.
 */
@Controller
@RequestMapping("/test-errors")
@PermitAll
public class ErrorTestController {
    
    /**
     * Test 404 error by accessing a non-existent page.
     * URL: /test-errors/404
     */
    @GetMapping("/404")
    public String test404() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test 404 error page");
    }
    
    /**
     * Test 500 internal server error.
     * URL: /test-errors/500
     */
    @GetMapping("/500")
    public String test500() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Test internal server error");
    }
    
    /**
     * Test 400 bad request error.
     * URL: /test-errors/400
     */
    @GetMapping("/400")
    public String test400() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test bad request error");
    }
    
    /**
     * Test 403 forbidden error.
     * URL: /test-errors/403
     */
    @GetMapping("/403")
    public String test403() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Test forbidden access error");
    }
    
    /**
     * Test 503 service unavailable error.
     * URL: /test-errors/503
     */
    @GetMapping("/503")
    public String test503() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Test service unavailable error");
    }
    
    /**
     * Test generic error with custom status code.
     * URL: /test-errors/{statusCode}
     */
    @GetMapping("/{statusCode}")
    public String testCustomError(@PathVariable int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        throw new ResponseStatusException(status, "Test error with status code " + statusCode);
    }
    
    /**
     * Test runtime exception (will result in 500 error).
     * URL: /test-errors/runtime-exception
     */
    @GetMapping("/runtime-exception")
    public String testRuntimeException() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Test runtime exception for error handling");
    }
    
    /**
     * Test null pointer exception (will result in 500 error).
     * URL: /test-errors/null-pointer
     */
    @GetMapping("/null-pointer")
    public String testNullPointer() {
        throw new NullPointerException("Test null pointer exception for error handling");
    }
}
