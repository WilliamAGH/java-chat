package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.domain.errors.ApiSuccessResponse;
import jakarta.annotation.security.PermitAll;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes a CSRF refresh endpoint so SPA clients can recover after session drift.
 *
 * <p>The endpoint is safe/idempotent and forces token materialization through Spring Security so
 * clients receive a fresh cookie before retrying state-changing requests.</p>
 */
@RestController
@RequestMapping("/api/security")
@PermitAll
@PreAuthorize("permitAll()")
public final class CsrfController {
    private static final String CSRF_REFRESH_SUCCESS_MESSAGE = "CSRF token refreshed";

    /**
     * Refreshes the CSRF token cookie for the current browser session.
     *
     * @param csrfToken token resolved from the current request context
     * @return non-cacheable success payload once token issuance is guaranteed
     */
    @GetMapping("/csrf")
    public ResponseEntity<ApiSuccessResponse> refreshCsrfToken(CsrfToken csrfToken) {
        String tokenValue = Objects.requireNonNull(csrfToken, "CSRF token should be available for refresh endpoint")
                .getToken();
        if (tokenValue.isBlank()) {
            throw new IllegalStateException("CSRF token refresh returned blank token");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(ApiSuccessResponse.success(CSRF_REFRESH_SUCCESS_MESSAGE));
    }
}
