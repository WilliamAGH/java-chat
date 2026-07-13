package com.williamcallahan.javachat.adapters.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Returns JSON 403 responses with clear invalid-CSRF messaging for API callers.
 */
public final class CsrfAccessDeniedHandler implements AccessDeniedHandler {
    private static final String CSRF_INVALID_MESSAGE =
            "CSRF token missing or invalid. Refresh the page and retry the request.";

    private final ObjectMapper objectMapper;

    /**
     * Creates the handler using the shared ObjectMapper for JSON serialization.
     *
     * @param objectMapper Spring-managed JSON mapper
     */
    public CsrfAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Writes a JSON 403 response tailored for CSRF failures.
     *
     * @param httpRequest incoming HTTP request
     * @param httpResponse outgoing HTTP response
     * @param accessDeniedException access denied exception from Spring Security
     * @throws IOException when the response cannot be written
     * @throws ServletException when the servlet container rejects the write
     */
    @Override
    public void handle(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        if (httpResponse.isCommitted()) {
            return;
        }

        ApiErrorResponse csrfError = ApiErrorResponse.error(CSRF_INVALID_MESSAGE);

        httpResponse.setStatus(HttpStatus.FORBIDDEN.value());
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(httpResponse.getOutputStream(), csrfError);
    }
}
