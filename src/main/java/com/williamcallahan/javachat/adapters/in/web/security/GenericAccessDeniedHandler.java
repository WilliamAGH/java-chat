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
 * Returns a generic JSON 403 response for non-CSRF access denials.
 *
 * <p>Installed as the default branch of the composed {@link AccessDeniedHandler}
 * wired in {@code SecurityConfig}, so authenticated callers denied for non-CSRF
 * reasons (for example a controller-thrown {@link AccessDeniedException}) no
 * longer receive the CSRF-specific message emitted by
 * {@link CsrfAccessDeniedHandler}. CSRF failures keep their tailored messaging
 * because {@code SecurityConfig} routes {@code MissingCsrfTokenException} and
 * {@code InvalidCsrfTokenException} to {@link CsrfAccessDeniedHandler} before
 * this default handler is reached.
 */
public final class GenericAccessDeniedHandler implements AccessDeniedHandler {
    private static final String ACCESS_DENIED_MESSAGE = "Access denied.";

    private final ObjectMapper objectMapper;

    /**
     * Creates the handler using the shared ObjectMapper for JSON serialization.
     *
     * @param objectMapper Spring-managed JSON mapper
     */
    public GenericAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Writes a generic JSON 403 response that does not claim a CSRF failure.
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

        ApiErrorResponse accessDeniedError = ApiErrorResponse.error(ACCESS_DENIED_MESSAGE);

        httpResponse.setStatus(HttpStatus.FORBIDDEN.value());
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(httpResponse.getOutputStream(), accessDeniedError);
    }
}
