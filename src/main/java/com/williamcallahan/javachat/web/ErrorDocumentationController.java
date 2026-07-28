package com.williamcallahan.javachat.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders internal browser error dispatches from classpath HTML pages.
 *
 * <p>{@link CustomErrorController} forwards browser failures here with the original
 * servlet error status. Direct catalog access is rejected because these resources are
 * response templates, not public API documentation.</p>
 */
@RestController
@RequestMapping("/errors")
public class ErrorDocumentationController {

    private static final Logger log = LoggerFactory.getLogger(ErrorDocumentationController.class);

    private static final String BROWSER_ERROR_PAGE_ROOT = "errors/";
    private static final String HTML_EXTENSION = ".html";

    /**
     * Rejects direct access to the former public error-documentation catalog.
     *
     * @return a not-found response because browser error pages are internal dispatch targets
     */
    @GetMapping({"", "/"})
    public ResponseEntity<Void> errorCatalog() {
        return ResponseEntity.notFound().build();
    }

    /**
     * Renders a Java Chat browser error page for an active servlet error dispatch.
     *
     * @param errorType internal status-specific page name
     * @return HTML with the original error status, or 404 for direct catalog access
     */
    @RequestMapping(
            value = "/{errorType}",
            method = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.PATCH,
                RequestMethod.HEAD,
                RequestMethod.OPTIONS
            },
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorType(@PathVariable String errorType, HttpServletRequest request) {
        Optional<HttpStatus> errorStatus = resolveErrorStatus(request);
        if (errorStatus.isEmpty() || !isSafeErrorType(errorType)) {
            return ResponseEntity.notFound().build();
        }
        return serveHtmlFile(errorType, errorStatus.orElseThrow());
    }

    private boolean isSafeErrorType(String errorType) {
        return !errorType.isBlank()
                && errorType
                        .codePoints()
                        .allMatch(character ->
                                Character.isLowerCase(character) || Character.isDigit(character) || character == '-');
    }

    private ResponseEntity<String> serveHtmlFile(String errorType, HttpStatus errorStatus) {
        ClassPathResource errorPageResource =
                new ClassPathResource(BROWSER_ERROR_PAGE_ROOT + errorType + HTML_EXTENSION);
        if (!errorPageResource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream inputStream = errorPageResource.getInputStream()) {
            String errorPageHtml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.status(errorStatus)
                    .contentType(MediaType.TEXT_HTML)
                    .body(errorPageHtml);
        } catch (IOException exception) {
            log.error("Failed to read browser error page", exception);
            return ResponseEntity.internalServerError().build();
        }
    }

    private Optional<HttpStatus> resolveErrorStatus(HttpServletRequest request) {
        Object errorStatusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (errorStatusCode instanceof Integer statusCodeValue) {
            return Optional.ofNullable(HttpStatus.resolve(statusCodeValue));
        }
        return Optional.empty();
    }
}
