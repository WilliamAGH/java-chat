package com.williamcallahan.javachat.web;

import java.util.Objects;

/**
 * Identity payload for the authenticated-user endpoint.
 *
 * <p>Carries only claims every Clerk session token guarantees: the stable
 * Clerk user id ({@code sub}). Clients needing profile data read it from the
 * Clerk frontend SDK, which owns that data; duplicating it here would create a
 * second, staler source.
 *
 * @param userId Clerk user identifier from the token's {@code sub} claim
 */
public record AuthenticatedUserResponse(String userId) {

    /**
     * Validates that the user id is present.
     *
     * @throws NullPointerException when userId is null
     */
    public AuthenticatedUserResponse {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
