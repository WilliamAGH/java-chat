package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes whether this deployment can safely complete browser-created API-key login. */
@RestController
@RequestMapping("/api/security")
public final class ApiKeyAvailabilityController {

    private final ApiKeyLifecycle apiKeyLifecycle;

    /**
     * Creates the deployment-readiness boundary for API-key authorization.
     *
     * @param apiKeyLifecycle application-owned key lifecycle
     */
    public ApiKeyAvailabilityController(ApiKeyLifecycle apiKeyLifecycle) {
        this.apiKeyLifecycle = apiKeyLifecycle;
    }

    /** Returns success only when the server-side Clerk credential is configured. */
    @GetMapping("/api-key-availability")
    public ResponseEntity<Void> apiKeyAvailability() {
        return apiKeyLifecycle.isAvailable()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(503).build();
    }
}
