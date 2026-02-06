package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.domain.errors.ApiSuccessResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * Unit coverage for CSRF refresh endpoint behavior.
 */
class CsrfControllerTest {

    @Test
    void refreshCsrfToken_returnsSuccessPayload_andNoStoreCaching() {
        CsrfController csrfController = new CsrfController();
        CsrfToken csrfToken = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "fresh-token");

        ResponseEntity<ApiSuccessResponse> response = csrfController.refreshCsrfToken(csrfToken);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("success", response.getBody().status());
        assertEquals("CSRF token refreshed", response.getBody().message());
    }

    @Test
    void refreshCsrfToken_throwsWhenTokenIsBlank() {
        CsrfController csrfController = new CsrfController();
        CsrfToken blankToken = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", " ");

        assertThrows(IllegalStateException.class, () -> csrfController.refreshCsrfToken(blankToken));
    }
}
