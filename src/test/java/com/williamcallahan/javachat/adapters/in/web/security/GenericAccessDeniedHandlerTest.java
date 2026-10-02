package com.williamcallahan.javachat.adapters.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage for the generic non-CSRF access-denied response handler.
 */
class GenericAccessDeniedHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesGenericJson403ThatDoesNotClaimCsrfFailure() throws IOException, ServletException {
        GenericAccessDeniedHandler handler = new GenericAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException exception = new AccessDeniedException("API key identity is required for revocation");

        handler.handle(request, response, exception);

        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
        ApiErrorResponse body = objectMapper.readValue(response.getContentAsString(), ApiErrorResponse.class);
        assertEquals("error", body.status());
        assertEquals("Access denied.", body.message());
        assertNull(body.details());
        String serializedBody = response.getContentAsString();
        assertFalse(serializedBody.contains("CSRF"));
        assertFalse(serializedBody.contains("API key identity is required for revocation"));
    }

    @Test
    void leavesCommittedResponseUnchanged() throws IOException, ServletException {
        GenericAccessDeniedHandler handler = new GenericAccessDeniedHandler(objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        response.setStatus(HttpStatus.OK.value());

        handler.handle(request, response, new AccessDeniedException("ignored"));

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void rejectsNullObjectMapper() {
        assertThrows(NullPointerException.class, () -> new GenericAccessDeniedHandler(null));
    }
}
