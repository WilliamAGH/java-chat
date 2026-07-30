package com.williamcallahan.javachat.config;

import java.util.List;
import java.util.Locale;

/**
 * Clerk authentication configuration for the API boundary.
 *
 * <p>Issuer and JWKS locations use Spring's standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties; this section
 * carries only the Clerk-specific settings that have no Spring counterpart.
 */
public class ClerkConfig {

    private static final String AUTHORIZED_PARTIES_KEY = "app.clerk.authorized-parties";
    private static final String NULL_LIST_FMT = "%s must not be null.";

    /**
     * Browser origins allowed as the {@code azp} (authorized party) claim of a
     * Clerk session token. Clerk's manual JWT verification guide requires
     * rejecting tokens minted for other origins; an empty list rejects every
     * token that carries {@code azp}.
     */
    private List<String> authorizedParties = List.of();

    /**
     * Creates Clerk configuration with an empty authorized-party allowlist.
     */
    public ClerkConfig() {}

    /**
     * Validates Clerk settings during property binding.
     */
    public void validateConfiguration() {
        requireNonNullList(AUTHORIZED_PARTIES_KEY, authorizedParties);
    }

    /**
     * Returns origins accepted as the token's authorized party.
     *
     * @return allowed {@code azp} claim values
     */
    public List<String> getAuthorizedParties() {
        return List.copyOf(authorizedParties);
    }

    /**
     * Sets origins accepted as the token's authorized party.
     *
     * @param authorizedParties allowed {@code azp} claim values
     */
    public void setAuthorizedParties(final List<String> authorizedParties) {
        this.authorizedParties = requireNonNullList(AUTHORIZED_PARTIES_KEY, authorizedParties);
    }

    private static List<String> requireNonNullList(final String propertyKey, final List<String> entries) {
        if (entries == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_LIST_FMT, propertyKey));
        }
        return List.copyOf(entries);
    }
}
