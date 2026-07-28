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
import org.springframework.web.bind.annotation.RequestMapping;
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

    private static final String BROWSER_ERROR_PAGE = "errors/browser-error.html";

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
     * Renders the Java Chat browser error page for an active servlet error dispatch.
     *
     * @return HTML with the original error status, or 404 for direct catalog access
     */
    @GetMapping(value = "/browser-error", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorPage(HttpServletRequest request) {
        Optional<HttpStatus> errorStatus = resolveErrorStatus(request);
        if (errorStatus.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return serveHtmlFile(errorStatus.orElseThrow());
    }

    private ResponseEntity<String> serveHtmlFile(HttpStatus errorStatus) {
        ClassPathResource errorPageResource = new ClassPathResource(BROWSER_ERROR_PAGE);
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
