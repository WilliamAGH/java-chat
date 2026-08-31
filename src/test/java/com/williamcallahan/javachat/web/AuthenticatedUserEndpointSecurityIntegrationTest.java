package com.williamcallahan.javachat.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.williamcallahan.javachat.adapters.out.clerk.ClerkApiKeyVerifier;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import com.williamcallahan.javachat.model.KnowledgeGroup;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.KnowledgeBaseInventoryService;
import io.qdrant.client.QdrantClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Clerk-enabled security chain end to end: anonymous callers are
 * rejected while verified session tokens and API keys reach the authenticated
 * endpoints ({@code /api/me}, {@code /api/me/api-key}, {@code /api/knowledge/groups}).
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
    private static final String CLERK_API_KEY_SECRET = "ak_secret_0123456789abcdef0123456789abcdef";
    private static final String CLERK_API_KEY_ID = "ak_0123456789abcdef0123456789abcdef";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @MockitoBean
    ClerkApiKeyVerifier clerkApiKeyVerifier;

    @MockitoBean
    KnowledgeBaseInventoryService knowledgeBaseInventoryService;

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousRequestToKnowledgeGroupsIsRejected() throws Exception {
        mockMvc.perform(get("/api/knowledge/groups")).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedApiKeyReachesKnowledgeGroups() throws Exception {
        when(clerkApiKeyVerifier.verify(CLERK_API_KEY_SECRET))
                .thenReturn(Optional.of(new VerifiedApiKey(CLERK_API_KEY_ID, CLERK_USER_ID)));
        when(knowledgeBaseInventoryService.listKnowledgeGroups())
                .thenReturn(List.of(new KnowledgeGroup("chat-docs", "DOCS", "oracle/javase/25/api", 10)));

        mockMvc.perform(get("/api/knowledge/groups").header("Authorization", "Bearer " + CLERK_API_KEY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].collection").value("chat-docs"))
                .andExpect(jsonPath("$[0].name").value("oracle/javase/25/api"))
                .andExpect(jsonPath("$[0].chunks").value(10));
    }

    @Test
    void verifiedClerkSessionReachesKnowledgeGroups() throws Exception {
        when(knowledgeBaseInventoryService.listKnowledgeGroups()).thenReturn(List.of());

        mockMvc.perform(get("/api/knowledge/groups")
                        .with(jwt().jwt(sessionToken -> sessionToken.subject(CLERK_USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void verifiedClerkSessionReceivesItsUserId() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(sessionToken -> sessionToken.subject(CLERK_USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CLERK_USER_ID));
    }

    @Test
    void verifiedApiKeyBypassesSessionJwtAuthentication() throws Exception {
        when(clerkApiKeyVerifier.verify(CLERK_API_KEY_SECRET))
                .thenReturn(Optional.of(new VerifiedApiKey(CLERK_API_KEY_ID, CLERK_USER_ID)));

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + CLERK_API_KEY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CLERK_USER_ID));
    }

    @Test
    void verifiedApiKeyCanRevokeItself() throws Exception {
        when(clerkApiKeyVerifier.verify(CLERK_API_KEY_SECRET))
                .thenReturn(Optional.of(new VerifiedApiKey(CLERK_API_KEY_ID, CLERK_USER_ID)));

        mockMvc.perform(delete("/api/me/api-key").header("Authorization", "Bearer " + CLERK_API_KEY_SECRET))
                .andExpect(status().isNoContent());

        verify(clerkApiKeyVerifier).revoke(CLERK_API_KEY_ID);
    }

    @Test
    void publicChatSurfaceStaysAnonymous() throws Exception {
        mockMvc.perform(get("/api/security/csrf")).andExpect(status().isOk());
    }
}
