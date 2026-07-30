package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Verifies the fail-closed posture of an environment where Clerk is not
 * configured (the dev and prod profiles each bind their own instance): no JWKS
 * property means no resource server, so the identity endpoint deterministically
 * denies everything while the public chat surface stays reachable.
 */
@SpringBootTest(properties = "spring.ai.vectorstore.qdrant.port=1")
@AutoConfigureMockMvc
class AuthenticatedUserEndpointClerkDisabledIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @Test
    void identityEndpointDeniesEveryRequestWithoutClerkConfiguration() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isForbidden());
    }

    @Test
    void bearerTokenGrantsNothingWithoutClerkConfiguration() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer forged-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicChatSurfaceStaysAnonymous() throws Exception {
        mockMvc.perform(get("/api/security/csrf")).andExpect(status().isOk());
    }

    @Test
    void everyResponseCarriesContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/api/security/csrf"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }
}
