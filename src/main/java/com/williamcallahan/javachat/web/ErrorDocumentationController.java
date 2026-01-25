package com.williamcallahan.javachat.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
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
 */
@RestController
@RequestMapping("/errors")
public class ErrorDocumentationController {

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
    public ResponseEntity<String> index() {
        return serveHtmlFile(INDEX_FILE);
    }

    /**
     * Serves a specific error type documentation page.
     *
     * @param errorType error type slug (e.g., {@code not-found}, {@code validation-failed})
     * @return HTML for the requested error type
     */
    @GetMapping(value = "/{errorType}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorType(@PathVariable String errorType) {
        if (!errorType.matches(ERROR_SLUG_PATTERN)) {
            return ResponseEntity.notFound().build();
        }
        return serveHtmlFile(errorType + HTML_EXTENSION);
    }

    private ResponseEntity<String> serveHtmlFile(String filename) {
        ClassPathResource resource = new ClassPathResource(ERRORS_PATH + filename);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(content);
        } catch (IOException exception) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
