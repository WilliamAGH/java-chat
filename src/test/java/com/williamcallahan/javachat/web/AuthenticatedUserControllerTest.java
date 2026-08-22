package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.williamcallahan.javachat.adapters.in.web.security.ClerkApiKeyAuthenticationToken;
import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit coverage for the authenticated-user identity endpoint.
 */
class AuthenticatedUserControllerTest {

    private static final String CLERK_USER_ID = "user_2abcDEFGHijkLMNopq";

    @Test
    void currentUser_returnsClerkSubject_andNoStoreCaching() {
        AuthenticatedUserController controller = new AuthenticatedUserController(mock(ApiKeyLifecycle.class));
        Jwt clerkSessionToken = Jwt.withTokenValue("clerk-session-token")
                .header("alg", "RS256")
                .subject(CLERK_USER_ID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        ResponseEntity<AuthenticatedUserResponse> identityResponse =
                controller.currentUser(new JwtAuthenticationToken(clerkSessionToken));

        assertEquals(200, identityResponse.getStatusCode().value());
        assertTrue(identityResponse.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(CLERK_USER_ID, identityResponse.getBody().userId());
    }

    @Test
    void revokesAuthenticatedApiKey() {
        ApiKeyLifecycle apiKeyLifecycle = mock(ApiKeyLifecycle.class);
        AuthenticatedUserController controller = new AuthenticatedUserController(apiKeyLifecycle);
        ClerkApiKeyAuthenticationToken clerkApiKey =
                new ClerkApiKeyAuthenticationToken("ak_0123456789abcdef0123456789abcdef", CLERK_USER_ID);

        ResponseEntity<?> revocationResponse = controller.revokeCurrentApiKey(clerkApiKey);

        assertEquals(204, revocationResponse.getStatusCode().value());
        verify(apiKeyLifecycle).revoke("ak_0123456789abcdef0123456789abcdef");
    }
}
