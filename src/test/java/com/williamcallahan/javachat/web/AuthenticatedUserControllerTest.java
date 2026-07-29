package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit coverage for the authenticated-user identity endpoint.
 */
class AuthenticatedUserControllerTest {

    private static final String CLERK_USER_ID = "user_2abcDEFGHijkLMNopq";

    @Test
    void currentUser_returnsClerkSubject_andNoStoreCaching() {
        AuthenticatedUserController controller = new AuthenticatedUserController();
        Jwt clerkSessionToken = Jwt.withTokenValue("clerk-session-token")
                .header("alg", "RS256")
                .subject(CLERK_USER_ID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        ResponseEntity<AuthenticatedUserResponse> identityResponse = controller.currentUser(clerkSessionToken);

        assertEquals(200, identityResponse.getStatusCode().value());
        assertTrue(identityResponse.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(CLERK_USER_ID, identityResponse.getBody().userId());
    }
}
