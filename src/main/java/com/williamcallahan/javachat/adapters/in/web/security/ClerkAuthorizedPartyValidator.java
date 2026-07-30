package com.williamcallahan.javachat.adapters.in.web.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects Clerk session tokens minted for an origin this deployment does not serve.
 *
 * <p>Clerk's manual JWT verification guide requires checking the {@code azp}
 * (authorized party) claim against the application's permitted origins: a
 * signature-valid token stolen from another Clerk-backed site must not grant
 * access here. Tokens without {@code azp} (non-browser flows such as backend
 * machine tokens) pass through and rely on issuer plus signature validation.
 */
public final class ClerkAuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    private static final String AUTHORIZED_PARTY_CLAIM = "azp";
    private static final String REJECTION_ERROR_CODE = "invalid_token";
    private static final String REJECTION_DESCRIPTION = "The azp claim is not an authorized party";

    private final Set<String> allowedAuthorizedParties;

    /**
     * Creates a validator for the configured origin allowlist.
     *
     * @param allowedAuthorizedParties origins accepted as the {@code azp} claim;
     *     an empty list rejects every token that carries the claim
     */
    public ClerkAuthorizedPartyValidator(List<String> allowedAuthorizedParties) {
        this.allowedAuthorizedParties = Set.copyOf(allowedAuthorizedParties);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt sessionToken) {
        String authorizedParty = sessionToken.getClaimAsString(AUTHORIZED_PARTY_CLAIM);
        if (authorizedParty == null || allowedAuthorizedParties.contains(authorizedParty)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(REJECTION_ERROR_CODE, REJECTION_DESCRIPTION, null));
    }
}
