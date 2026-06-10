package com.williamcallahan.javachat.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves RFC 9457 error type documentation pages.
 *
 * <p>Maps extension-less URLs (e.g., {@code /errors/not-found}) to static HTML files
 * (e.g., {@code /static/errors/not-found.html}) so that ProblemDetail type URIs
 * resolve to human-readable documentation.
 *
 * <p>These pages serve two roles: browsed directly they are documentation (200), but
 * {@link CustomErrorController} also forwards error dispatches here to render the same
 * HTML as the error response body. In that case the original error status MUST be
 * preserved — answering 200 for a missing resource makes browsers treat a failed
 * module-script fetch as success and fail with a strict-MIME error instead of a 404.</p>
 */
@RestController
@RequestMapping("/errors")
public class ErrorDocumentationController {

    private static final Logger log = LoggerFactory.getLogger(ErrorDocumentationController.class);

    private static final String ERRORS_PATH = "static/errors/";
    private static final String ERROR_SLUG_PATTERN = "^[a-z0-9-]+$";
    private static final String INDEX_FILE = "index.html";
    private static final String HTML_EXTENSION = ".html";
    private static final String ROOT_PATH = "";
    private static final String ROOT_SLASH = "/";

    /**
     * Serves the error catalog index.
     *
     * @return HTML for the error catalog
     */
    @GetMapping(
            value = {ROOT_PATH, ROOT_SLASH},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index(HttpServletRequest request) {
        return serveHtmlFile(INDEX_FILE, request);
    }

    /**
     * Serves a specific error type documentation page.
     *
     * @param errorType error type slug (e.g., {@code not-found}, {@code validation-failed})
     * @return HTML for the requested error type
     */
    @GetMapping(value = "/{errorType}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorType(@PathVariable String errorType, HttpServletRequest request) {
        if (!errorType.matches(ERROR_SLUG_PATTERN)) {
            return ResponseEntity.notFound().build();
        }
        return serveHtmlFile(errorType + HTML_EXTENSION, request);
    }

    private ResponseEntity<String> serveHtmlFile(String filename, HttpServletRequest request) {
        ClassPathResource resource = new ClassPathResource(ERRORS_PATH + filename);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.status(resolveResponseStatus(request))
                    .contentType(MediaType.TEXT_HTML)
                    .body(content);
        } catch (IOException exception) {
            log.error("Failed to read error documentation page {}", filename, exception);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Preserves the original error status when rendering as an error-dispatch body.
     *
     * <p>The {@code ERROR_STATUS_CODE} attribute survives {@link CustomErrorController}'s
     * forward because it is set on the same request; it is absent when a reader browses
     * the documentation directly, which keeps direct visits at 200.</p>
     */
    private HttpStatus resolveResponseStatus(HttpServletRequest request) {
        Object errorStatusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (errorStatusCode instanceof Integer statusCodeValue) {
            HttpStatus resolvedStatus = HttpStatus.resolve(statusCodeValue);
            if (resolvedStatus != null) {
                return resolvedStatus;
            }
        }
        return HttpStatus.OK;
    }
}
