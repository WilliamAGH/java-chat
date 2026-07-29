package com.williamcallahan.javachat.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.service.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Clerk-enabled security chain end to end: anonymous callers are
 * rejected while verified session tokens reach the identity endpoint.
 *
 * <p>Runs with the dev-shaped Clerk properties so the conditional
 * {@code clerkJwtDecoder} bean and the resource-server wiring are active,
 * mirroring dev.javachat.ai. The decoder itself is never invoked because
 * {@code jwt()} injects the authentication directly; no network access occurs.
 */
@SpringBootTest(
        properties = {
            "spring.ai.vectorstore.qdrant.port=1",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://romantic-cow-6.clerk.accounts.dev",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://romantic-cow-6.clerk.accounts.dev/.well-known/jwks.json",
            "app.clerk.authorized-parties=http://localhost:5173"
        })
@AutoConfigureMockMvc
class AuthenticatedUserEndpointSecurityIntegrationTest {

    private static final String CLERK_USER_ID = "user_2abcDEFGHijkLMNopq";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedClerkSessionReceivesItsUserId() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(sessionToken -> sessionToken.subject(CLERK_USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CLERK_USER_ID));
    }

    @Test
    void publicChatSurfaceStaysAnonymous() throws Exception {
        mockMvc.perform(get("/api/security/csrf")).andExpect(status().isOk());
    }
}
