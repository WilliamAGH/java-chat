package com.williamcallahan.javachat.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.adapters.out.clerk.ClerkApiKeyVerifier;
import com.williamcallahan.javachat.application.knowledge.KnowledgeBaseInventoryUseCase;
import com.williamcallahan.javachat.service.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the access-denied routing of {@code DELETE /api/me/api-key} under the
 * Clerk-enabled security chain: CSRF failures keep their CSRF-specific message
 * while authenticated, non-CSRF denials receive a generic 403 instead of the
 * false "CSRF token missing or invalid" message.
 *
 * <p>Runs with the dev-shaped Clerk properties so the conditional
 * {@code clerkJwtDecoder} bean and the resource-server wiring are active,
 * mirroring the production security chain. The decoder itself is never invoked
 * because {@code jwt()} injects the authentication directly; no network access
 * occurs.
 */
@SpringBootTest(
        properties = {
            "spring.ai.vectorstore.qdrant.port=1",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://romantic-cow-6.clerk.accounts.dev",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://romantic-cow-6.clerk.accounts.dev/.well-known/jwks.json",
            "app.clerk.authorized-parties=http://localhost:5173"
        })
@AutoConfigureMockMvc
class RevokeApiKeyAuthIntegrationTest {

    private static final String CLERK_USER_ID = "user_2abcDEFGHijkLMNopq";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String CSRF_INVALID_MESSAGE =
            "CSRF token missing or invalid. Refresh the page and retry the request.";
    private static final String ACCESS_DENIED_MESSAGE = "Access denied.";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @MockitoBean
    ClerkApiKeyVerifier clerkApiKeyVerifier;

    @MockitoBean
    KnowledgeBaseInventoryUseCase knowledgeBaseInventoryUseCase;

    @Test
    void anonymousWithoutCsrfIsBlockedWithTheCsrfMessage() throws Exception {
        mockMvc.perform(delete("/api/me/api-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(CSRF_INVALID_MESSAGE));
    }

    @Test
    void anonymousWithValidCsrfIsUnauthorizedByTheEntryPoint() throws Exception {
        String csrfToken = fetchCsrfToken();

        mockMvc.perform(delete("/api/me/api-key")
                        .cookie(new Cookie(CSRF_COOKIE_NAME, csrfToken))
                        .header(CSRF_HEADER_NAME, csrfToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clerkJwtWithValidCsrfReceivesGenericForbiddenNotCsrfMessage() throws Exception {
        String csrfToken = fetchCsrfToken();

        mockMvc.perform(delete("/api/me/api-key")
                        .with(jwt().jwt(token -> token.subject(CLERK_USER_ID)))
                        .cookie(new Cookie(CSRF_COOKIE_NAME, csrfToken))
                        .header(CSRF_HEADER_NAME, csrfToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(ACCESS_DENIED_MESSAGE))
                .andExpect(content().string(Matchers.not(Matchers.containsString("CSRF"))));
    }

    private String fetchCsrfToken() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/security/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        for (Cookie cookie : csrfResult.getResponse().getCookies()) {
            if (CSRF_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new AssertionError("CSRF cookie was not issued by /api/security/csrf");
    }
}
