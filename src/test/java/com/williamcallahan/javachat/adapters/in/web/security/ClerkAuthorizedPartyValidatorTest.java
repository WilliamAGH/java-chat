package com.williamcallahan.javachat.adapters.in.web.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit coverage for Clerk authorized-party (azp) claim validation.
 */
class ClerkAuthorizedPartyValidatorTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String FOREIGN_ORIGIN = "https://attacker.example";

    @Test
    void acceptsTokenWhoseAuthorizedPartyIsAllowed() {
        ClerkAuthorizedPartyValidator validator = new ClerkAuthorizedPartyValidator(List.of(ALLOWED_ORIGIN));

        assertFalse(validator
                .validate(sessionTokenWithAuthorizedParty(ALLOWED_ORIGIN))
                .hasErrors());
    }

    @Test
    void rejectsTokenMintedForAForeignOrigin() {
        ClerkAuthorizedPartyValidator validator = new ClerkAuthorizedPartyValidator(List.of(ALLOWED_ORIGIN));

        assertTrue(validator
                .validate(sessionTokenWithAuthorizedParty(FOREIGN_ORIGIN))
                .hasErrors());
    }

    @Test
    void acceptsTokenWithoutAuthorizedPartyClaim() {
        ClerkAuthorizedPartyValidator validator = new ClerkAuthorizedPartyValidator(List.of(ALLOWED_ORIGIN));
        Jwt machineToken = sessionTokenBuilder().build();

        assertFalse(validator.validate(machineToken).hasErrors());
    }

    @Test
    void rejectsAuthorizedPartyTokenWhenAllowlistIsEmpty() {
        ClerkAuthorizedPartyValidator validator = new ClerkAuthorizedPartyValidator(List.of());

        assertTrue(validator
                .validate(sessionTokenWithAuthorizedParty(ALLOWED_ORIGIN))
                .hasErrors());
    }

    private static Jwt sessionTokenWithAuthorizedParty(String authorizedParty) {
        return sessionTokenBuilder().claim("azp", authorizedParty).build();
    }

    private static Jwt.Builder sessionTokenBuilder() {
        return Jwt.withTokenValue("clerk-session-token")
                .header("alg", "RS256")
                .subject("user_2abcDEFGHijkLMNopq")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }
}
