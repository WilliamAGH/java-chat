package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.adapters.out.clerk.ClerkApiKeyVerifier;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import com.williamcallahan.javachat.web.CsrfController;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that the browser-readable CSRF cookie remains valid across stateless requests.
 */
@WebMvcTest(controllers = CsrfController.class)
@Import({AppProperties.class, SecurityConfig.class, WebMvcConfig.class, SecurityConfigTest.ProtectedPostController.class
})
class SecurityConfigTest {
    private static final String CSRF_REFRESH_ENDPOINT = "/api/security/csrf";
    private static final String CSRF_PROTECTED_ENDPOINT = "/api/chat/stream";
    private static final String API_KEY_SUBJECT_ENDPOINT = "/api/me";
    private static final String LOGOUT_ENDPOINT = "/logout";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String CSRF_COOKIE_ROOT_PATH = "/";
    private static final String CSRF_COOKIE_SAME_SITE_ATTRIBUTE = "SameSite";
    private static final String CSRF_COOKIE_SAME_SITE_POLICY = "Lax";
    private static final int CSRF_COOKIE_DELETION_MAX_AGE_SECONDS = 0;
    private static final String CSRF_INVALID_MESSAGE =
            "CSRF token missing or invalid. Refresh the page and retry the request.";
    private static final String CONTENT_HASHED_ASSET_PATH = "/assets/application-a1b2c3d4.js";
    private static final String CONTENT_HASHED_ASSET_CACHE_CONTROL = "max-age=31536000, public, immutable";
    private static final String FONT_ASSET_PATH = "/fonts/Fraunces-Variable.ttf";
    private static final String SITE_MANIFEST_PATH = "/site.webmanifest";
    private static final String UNVERSIONED_STATIC_RESOURCE_CACHE_CONTROL = "max-age=3600, public";
    private static final String HTML_SHELL_PATH = "/index.html";
    private static final String NON_CACHEABLE_RESOURCE_CACHE_CONTROL = "no-store";
    private static final String CSRF_REFRESH_CACHE_CONTROL = "no-store, must-revalidate";
    private static final String API_KEY_SECRET = "ak_secret_0123456789abcdef0123456789abcdef";
    private static final String API_KEY_BEARER = "Bearer " + API_KEY_SECRET;
    private static final String SESSION_TOKEN_BEARER = "Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature";
    private static final String API_KEY_UNAVAILABLE_MESSAGE =
            "API key verification is temporarily unavailable. Please retry.";
    private static final String API_KEY_ID = "ak_0123456789abcdef0123456789abcdef";
    private static final String API_KEY_SUBJECT = "user_0123456789abcdefghijklmnopq";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClerkApiKeyVerifier clerkApiKeyVerifier;

    @Test
    void acceptsCookieAndHeaderTokenWithoutServerSession() throws Exception {
        MvcResult csrfRefreshExchange = requestCsrfCookie();
        Cookie csrfCookie = csrfRefreshExchange.getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);
        assertBrowserReadableCsrfCookieAttributes(csrfCookie);
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
    void skipsCsrfExchangeForApiKeyBearerRequests() throws Exception {
        // Reaching key verification at all proves CSRF was bypassed: with a token
        // requirement in force this request would have stopped at 403 instead.
        when(clerkApiKeyVerifier.verify(API_KEY_SECRET))
                .thenThrow(new ApiKeyOperationUnavailableException("Clerk unavailable"));
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, API_KEY_BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(API_KEY_UNAVAILABLE_MESSAGE));
    }

    @Test
    void rejectsUnknownApiKeyAfterCsrfExemption() throws Exception {
        when(clerkApiKeyVerifier.verify(API_KEY_SECRET)).thenReturn(Optional.empty());

        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, API_KEY_BEARER))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void keepsCsrfProtectionForBearerTokensThatAreNotApiKeys() throws Exception {
        // The exemption must stay narrow: a Clerk session token still travels
        // alongside cookies, so widening it would reopen the cross-site path.
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, SESSION_TOKEN_BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
    }

    @Test
    void authenticatesApiKeyBearerWithoutCsrfExchange() throws Exception {
        when(clerkApiKeyVerifier.verify(API_KEY_SECRET))
                .thenReturn(Optional.of(new VerifiedApiKey(API_KEY_ID, API_KEY_SUBJECT)));

        mockMvc.perform(post(API_KEY_SUBJECT_ENDPOINT).header(HttpHeaders.AUTHORIZATION, API_KEY_BEARER))
                .andExpect(status().isOk())
                .andExpect(content().string(API_KEY_SUBJECT));
    }

    @Test
    void returnsJsonWhenCsrfTokenIsMissing() throws Exception {
        mockMvc.perform(post(CSRF_PROTECTED_ENDPOINT))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
    }

    @Test
    void issuesSecureBrowserReadableCsrfCookieOverHttps() throws Exception {
        Cookie issuedCsrfCookie = requestSecureCsrfCookie().getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(issuedCsrfCookie);

        assertTrue(issuedCsrfCookie.getSecure());
        assertBrowserReadableCsrfCookieAttributes(issuedCsrfCookie);
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
        Cookie issuedCsrfCookie = requestSecureCsrfCookie().getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(issuedCsrfCookie);

        MvcResult logoutExchange = mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .secure(true)
                        .cookie(issuedCsrfCookie)
                        .header(CSRF_HEADER_NAME, issuedCsrfCookie.getValue()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Cookie deletedCsrfCookie = logoutExchange.getResponse().getCookie(CSRF_COOKIE_NAME);
        assertNotNull(deletedCsrfCookie);
        assertTrue(deletedCsrfCookie.getSecure());
        assertBrowserReadableCsrfCookieAttributes(deletedCsrfCookie);
        assertEquals(CSRF_COOKIE_DELETION_MAX_AGE_SECONDS, deletedCsrfCookie.getMaxAge());
    }

    @Test
    void servesContentHashedAssetsWithImmutablePublicCachingWithoutCsrfCookie() throws Exception {
        mockMvc.perform(get(CONTENT_HASHED_ASSET_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CONTENT_HASHED_ASSET_CACHE_CONTROL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void servesUnversionedStaticResourcesWithBoundedPublicCachingWithoutCsrfCookie() throws Exception {
        mockMvc.perform(head(FONT_ASSET_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, UNVERSIONED_STATIC_RESOURCE_CACHE_CONTROL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertBoundedPublicStaticResource(SITE_MANIFEST_PATH);
    }

    @Test
    void keepsHtmlShellUncacheableWithoutIssuingCsrfCookie() throws Exception {
        mockMvc.perform(get(HTML_SHELL_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NON_CACHEABLE_RESOURCE_CACHE_CONTROL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private MvcResult requestCsrfCookie() throws Exception {
        return mockMvc.perform(get(CSRF_REFRESH_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CSRF_REFRESH_CACHE_CONTROL))
                .andReturn();
    }

    /**
     * Requests a CSRF cookie through the HTTPS transport path so cookie security attributes are observable.
     */
    private MvcResult requestSecureCsrfCookie() throws Exception {
        return mockMvc.perform(get(CSRF_REFRESH_ENDPOINT).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CSRF_REFRESH_CACHE_CONTROL))
                .andReturn();
    }

    private void assertBoundedPublicStaticResource(String staticResourcePath) throws Exception {
        mockMvc.perform(get(staticResourcePath))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, UNVERSIONED_STATIC_RESOURCE_CACHE_CONTROL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /**
     * Verifies browser-visible attributes shared by issued and deleted CSRF cookies.
     */
    private static void assertBrowserReadableCsrfCookieAttributes(Cookie csrfCookie) {
        assertEquals(CSRF_COOKIE_ROOT_PATH, csrfCookie.getPath());
        assertEquals(CSRF_COOKIE_SAME_SITE_POLICY, csrfCookie.getAttribute(CSRF_COOKIE_SAME_SITE_ATTRIBUTE));
        assertFalse(csrfCookie.isHttpOnly());
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

        /**
         * Returns the verified API-key subject so the real filter-chain outcome is observable.
         *
         * @param authentication API-key identity installed by the authentication filter
         * @return the owning Clerk subject
         */
        @PostMapping(value = API_KEY_SUBJECT_ENDPOINT, produces = MediaType.TEXT_PLAIN_VALUE)
        public ResponseEntity<String> apiKeySubject(Authentication authentication) {
            return ResponseEntity.ok(authentication.getName());
        }
    }
}
