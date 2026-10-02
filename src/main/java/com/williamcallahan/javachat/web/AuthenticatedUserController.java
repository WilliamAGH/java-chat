package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.adapters.in.web.security.ClerkApiKeyAuthenticationToken;
import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.domain.errors.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the identity of a verified Clerk session or personal API key.
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
    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserController.class);
    private static final String API_KEY_REVOCATION_UNAVAILABLE_LOG_MESSAGE = "Clerk API key revocation was unavailable";

    private final ApiKeyLifecycle apiKeyLifecycle;

    /**
     * Creates the identity boundary with Clerk key lifecycle ownership.
     *
     * @param apiKeyLifecycle application-owned key lifecycle
     */
    public AuthenticatedUserController(ApiKeyLifecycle apiKeyLifecycle) {
        this.apiKeyLifecycle = apiKeyLifecycle;
    }

    /**
     * Identifies the signed-in Clerk user.
     *
     * @param authentication verified session or API-key authentication
     * @return non-cacheable identity payload for the current user
     */
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUserResponse> currentUser(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(new AuthenticatedUserResponse(authentication.getName()));
    }

    /**
     * Revokes the API key used for this request before a CLI removes its local copy.
     *
     * @param authentication verified API-key authentication
     * @return empty success response after Clerk confirms revocation
     */
    @DeleteMapping("/me/api-key")
    public ResponseEntity<?> revokeCurrentApiKey(Authentication authentication) {
        if (!(authentication instanceof ClerkApiKeyAuthenticationToken clerkApiKey)) {
            throw new AccessDeniedException("API key identity is required for revocation");
        }
        try {
            apiKeyLifecycle.revoke(clerkApiKey.getCredentials());
        } catch (ApiKeyOperationUnavailableException unavailableClerk) {
            log.error(API_KEY_REVOCATION_UNAVAILABLE_LOG_MESSAGE, unavailableClerk);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiErrorResponse.error("API key revocation is temporarily unavailable. Please retry."));
        }
        return ResponseEntity.noContent().build();
    }
}
