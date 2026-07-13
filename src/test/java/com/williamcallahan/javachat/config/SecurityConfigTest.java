package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.web.CsrfController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that the browser-readable CSRF cookie remains valid across stateless requests.
 */
@WebMvcTest(controllers = CsrfController.class)
@Import({AppProperties.class, SecurityConfig.class, SecurityConfigTest.ProtectedPostController.class})
class SecurityConfigTest {
    private static final String CSRF_REFRESH_ENDPOINT = "/api/security/csrf";
    private static final String CSRF_PROTECTED_ENDPOINT = "/api/security/csrf-test";
    private static final String LOGOUT_ENDPOINT = "/logout";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String CSRF_COOKIE_SAME_SITE_POLICY = "Lax";
    private static final String CSRF_INVALID_MESSAGE =
            "CSRF token missing or invalid. Refresh the page and retry the request.";

    @Autowired
    MockMvc mockMvc;

    @Test
    void acceptsCookieAndHeaderTokenWithoutServerSession() throws Exception {
        MvcResult csrfRefreshExchange = requestCsrfCookie();
        Cookie csrfCookie = csrfRefreshExchange.getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);
        assertEquals(CSRF_COOKIE_SAME_SITE_POLICY, csrfCookie.getAttribute("SameSite"));
        assertFalse(csrfCookie.isHttpOnly());
        assertNull(csrfRefreshExchange.getRequest().getSession(false));
        assertNull(csrfRefreshExchange.getResponse().getCookie("JSESSIONID"));

        MvcResult protectedPostExchange = mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT)
                        .cookie(csrfCookie)
                        .header(CSRF_HEADER_NAME, csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertNull(protectedPostExchange.getRequest().getSession(false));
        assertNull(protectedPostExchange.getResponse().getCookie("JSESSIONID"));
    }

    @Test
    void returnsJsonWhenCsrfTokenIsMissing() throws Exception {
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
    }

    @Test
    void rejectsMismatchedAndSingleSidedCsrfTokens() throws Exception {
        Cookie csrfCookie = requestCsrfCookie().getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);

        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT)
                        .cookie(csrfCookie)
                        .header(CSRF_HEADER_NAME, csrfCookie.getValue() + "-mismatch"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT).cookie(csrfCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT).header(CSRF_HEADER_NAME, csrfCookie.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
    }

    @Test
    void deletesCsrfCookieImmediatelyOnLogout() throws Exception {
        Cookie csrfCookie = requestCsrfCookie().getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);

        MvcResult logoutExchange = mockMvc.perform(
                        post(LOGOUT_ENDPOINT).cookie(csrfCookie).header(CSRF_HEADER_NAME, csrfCookie.getValue()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Cookie deletedCsrfCookie = logoutExchange.getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(deletedCsrfCookie);
        assertEquals(0, deletedCsrfCookie.getMaxAge());
    }

    private MvcResult requestCsrfCookie() throws Exception {
        return mockMvc.perform(get(CSRF_REFRESH_ENDPOINT))
                .andExpect(status().isOk())
                .andReturn();
    }

    /**
     * Exposes a harmless state-changing route so the real security filter chain can be tested.
     */
    @RestController
    public static final class ProtectedPostController {

        /**
         * Confirms that Spring Security admitted a state-changing request.
         *
         * @return an empty success response
         */
        @PostMapping(CSRF_PROTECTED_ENDPOINT)
        public ResponseEntity<Void> acceptProtectedPost() {
            return ResponseEntity.noContent().build();
        }
    }
}
