package com.williamcallahan.javachat.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the identity of the caller's verified Clerk session.
 *
 * <p>Proves the Clerk JWT verification chain end to end: the SPA attaches its
 * session token, Spring Security validates signature, issuer, and authorized
 * party, and this endpoint echoes the authenticated user id. Anonymous
 * requests are rejected with 401 by the security chain before reaching here.
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
public final class AuthenticatedUserController {

    /**
     * Identifies the signed-in Clerk user.
     *
     * @param clerkSessionToken verified session JWT resolved by the resource server
     * @return non-cacheable identity payload for the current user
     */
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUserResponse> currentUser(@AuthenticationPrincipal Jwt clerkSessionToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(new AuthenticatedUserResponse(clerkSessionToken.getSubject()));
    }
}
