package com.williamcallahan.javachat.adapters.out.clerk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies presented Clerk API key secrets against the Clerk Backend API.
 *
 * <p>Clerk owns key lifecycle — creation, revocation, expiry — so every request
 * asks Clerk rather than trusting a local copy. That keeps revocation immediate
 * and lets Clerk record {@code last_used_at}, which a local cache would silently
 * freeze.
 *
 * <p>Per Clerk Backend API {@code POST /api_keys/verify} (spec version
 * {@value #CLERK_API_VERSION}), a syntactically wrong or unknown secret answers
 * {@code 400} or {@code 404}; a known key answers {@code 200} while still
 * reporting {@code revoked} and {@code expired} that the caller must honour.
 */
@Component
public class ClerkApiKeyVerifier implements ApiKeyLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClerkApiKeyVerifier.class);
    private static final String CLERK_VERIFY_ENDPOINT = "https://api.clerk.com/v1/api_keys/verify";
    private static final String CLERK_REVOKE_ENDPOINT = "https://api.clerk.com/v1/api_keys/{apiKeyId}/revoke";
    private static final String CLERK_API_VERSION_HEADER = "Clerk-API-Version";
    private static final String CLERK_API_VERSION = "2026-05-12";
    private static final Duration CLERK_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CLERK_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLERK_AVAILABILITY_RETRY_DELAY = Duration.ofSeconds(30);
    private static final int CLERK_VERIFICATION_MAXIMUM_CONCURRENCY = 8;
    private static final String CLERK_AVAILABILITY_PROBE_SECRET = "ak_secret_javachatavailabilityprobe0000000000000000";
    private static final String CLERK_SECRET_KEY_ENVIRONMENT_VARIABLE = "${CLERK_SECRET_KEY:}";
    private static final String SECRET_KEY_MISSING_MESSAGE =
            "Clerk API key verification requires CLERK_SECRET_KEY to be configured.";
    private static final String CLERK_FEATURE_DISABLED_CODE = "feature_not_enabled";
    private static final String FEATURE_DISABLED_MESSAGE = "Clerk API keys are not enabled for this instance (HTTP 403 "
            + CLERK_FEATURE_DISABLED_CODE + "). Enable API keys for this Clerk instance in the Clerk dashboard;"
            + " retrying cannot succeed.";

    private final RestClient clerkBackendApi;
    private final String clerkSecretKey;
    private final Semaphore verificationPermits = new Semaphore(CLERK_VERIFICATION_MAXIMUM_CONCURRENCY, true);
    // Latched the first time Clerk reports the API-keys feature off for this
    // instance. That state is operator-controlled and cannot clear itself within
    // a process, so continuing to advertise availability would invite clients
    // into a login that can never complete.
    private volatile ApiKeyFeatureState apiKeyFeatureState = ApiKeyFeatureState.UNKNOWN;
    private volatile long nextAvailabilityProbeNanos;

    /**
     * Builds the Clerk boundary with its own timeouts so a slow provider cannot
     * hold a request thread past the streaming budget.
     *
     * @param restClientBuilder Spring-managed builder carrying shared message converters
     * @param clerkSecretKey Clerk Backend API server credential, blank when unconfigured
     */
    @Autowired
    public ClerkApiKeyVerifier(
            RestClient.Builder restClientBuilder, @Value(CLERK_SECRET_KEY_ENVIRONMENT_VARIABLE) String clerkSecretKey) {
        this(
                restClientBuilder
                        .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                                .build(ClientHttpRequestFactorySettings.defaults()
                                        .withTimeouts(CLERK_CONNECT_TIMEOUT, CLERK_READ_TIMEOUT)))
                        .build(),
                clerkSecretKey);
    }

    ClerkApiKeyVerifier(RestClient clerkBackendApi, String clerkSecretKey) {
        this.clerkSecretKey = clerkSecretKey == null ? "" : clerkSecretKey.trim();
        this.clerkBackendApi = Objects.requireNonNull(clerkBackendApi, "clerkBackendApi")
                .mutate()
                .defaultHeaders(headers -> {
                    if (!this.clerkSecretKey.isEmpty()) {
                        headers.setBearerAuth(this.clerkSecretKey);
                    }
                    headers.set(CLERK_API_VERSION_HEADER, CLERK_API_VERSION);
                })
                .build();
    }

    /**
     * Reports whether key verification can run at all in this deployment.
     *
     * <p>Environments without a Clerk server credential reject every API key
     * rather than guessing, so the caller can skip the network round trip.
     *
     * <p>A configured credential is necessary but not sufficient: Clerk gates
     * API keys per instance, and a deployment whose instance has the feature off
     * can authenticate nothing. Reporting availability from the credential alone
     * told CLI clients to begin a login that Clerk then refused on every call.
     * The first readiness request sends one provider-valid unknown key and latches
     * Clerk's documented response, so anonymous polling cannot fan out upstream.
     *
     * @return true only after Clerk confirms the API-key verification feature
     */
    @Override
    public synchronized boolean isAvailable() {
        if (clerkSecretKey.isEmpty()) {
            return false;
        }
        if (apiKeyFeatureState == ApiKeyFeatureState.UNAVAILABLE
                && System.nanoTime() - nextAvailabilityProbeNanos >= 0) {
            apiKeyFeatureState = ApiKeyFeatureState.UNKNOWN;
        }
        if (apiKeyFeatureState != ApiKeyFeatureState.UNKNOWN) {
            return apiKeyFeatureState == ApiKeyFeatureState.ENABLED;
        }
        try {
            verify(CLERK_AVAILABILITY_PROBE_SECRET);
            if (apiKeyFeatureState != ApiKeyFeatureState.ENABLED) {
                log.warn("Clerk API-key availability probe returned no documented readiness response");
                apiKeyFeatureState = ApiKeyFeatureState.UNAVAILABLE;
            }
        } catch (ApiKeyOperationUnavailableException availabilityFailure) {
            log.warn("Clerk API-key availability probe failed", availabilityFailure);
            apiKeyFeatureState = apiKeyFeatureState == ApiKeyFeatureState.DISABLED
                    ? ApiKeyFeatureState.DISABLED
                    : ApiKeyFeatureState.UNAVAILABLE;
            nextAvailabilityProbeNanos = System.nanoTime() + CLERK_AVAILABILITY_RETRY_DELAY.toNanos();
        }
        return apiKeyFeatureState == ApiKeyFeatureState.ENABLED;
    }

    /**
     * Verifies one presented key secret with Clerk.
     *
     * @param presentedSecret the {@code ak_}-prefixed secret supplied by the caller
     * @return the verified key identity, or empty when Clerk rejected the secret
     *     or reported it revoked or expired
     * @throws ApiKeyOperationUnavailableException when Clerk could not answer,
     *     so an unanswered check never degrades into an anonymous request
     */
    @Override
    public Optional<VerifiedApiKey> verify(String presentedSecret) {
        requireConfiguredApiKeyFeature();
        if (!verificationPermits.tryAcquire()) {
            throw new ApiKeyOperationUnavailableException("Clerk API key verification capacity is exhausted");
        }
        ClerkApiKeyVerification verification;
        try {
            verification = clerkBackendApi
                    .post()
                    .uri(CLERK_VERIFY_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ClerkApiKeyVerificationRequest(presentedSecret))
                    .retrieve()
                    .body(ClerkApiKeyVerification.class);
        } catch (HttpStatusCodeException clerkRejection) {
            if (isRejectedCredential(clerkRejection.getStatusCode())) {
                if (isAvailabilityProbeRejection(presentedSecret, clerkRejection)) {
                    apiKeyFeatureState = ApiKeyFeatureState.ENABLED;
                }
                return Optional.empty();
            }
            if (isFeatureDisabled(clerkRejection)) {
                throw disabledFeatureException(clerkRejection);
            }
            throw new ApiKeyOperationUnavailableException(
                    "Clerk returned HTTP " + clerkRejection.getStatusCode().value() + " while verifying an API key",
                    clerkRejection);
        } catch (RestClientException transportFailure) {
            throw new ApiKeyOperationUnavailableException(
                    "Clerk API key verification request failed", transportFailure);
        } finally {
            verificationPermits.release();
        }
        if (verification == null) {
            throw new ApiKeyOperationUnavailableException("Clerk returned an empty verification response");
        }
        if (verification.revoked() == null || verification.expired() == null) {
            throw new ApiKeyOperationUnavailableException("Clerk returned incomplete API key lifecycle state");
        }
        if (verification.revoked() || verification.expired()) {
            return Optional.empty();
        }
        apiKeyFeatureState = ApiKeyFeatureState.ENABLED;
        VerifiedApiKey verifiedApiKey = new VerifiedApiKey(verification.id(), verification.subject());
        return Optional.of(verifiedApiKey);
    }

    /**
     * Revokes a verified personal key after an authenticated client requests logout.
     *
     * @param apiKeyId Clerk key identifier installed in the authenticated principal
     * @throws ApiKeyOperationUnavailableException when Clerk cannot confirm revocation
     */
    @Override
    public void revoke(String apiKeyId) {
        requireConfiguredApiKeyFeature();
        try {
            clerkBackendApi
                    .post()
                    .uri(CLERK_REVOKE_ENDPOINT, apiKeyId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClerkApiKeyRevocationRequest("JavaChat CLI logout"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException revocationFailure) {
            throw new ApiKeyOperationUnavailableException("Clerk API key revocation failed", revocationFailure);
        }
    }

    private void requireConfiguredApiKeyFeature() {
        if (clerkSecretKey.isEmpty()) {
            throw new ApiKeyOperationUnavailableException(SECRET_KEY_MISSING_MESSAGE);
        }
        if (apiKeyFeatureState == ApiKeyFeatureState.DISABLED) {
            throw new ApiKeyOperationUnavailableException(FEATURE_DISABLED_MESSAGE);
        }
    }

    /**
     * Treats only Clerk's credential-rejection statuses as "not authenticated";
     * every other status means Clerk failed to answer and must not be read as a
     * verdict on the key.
     */
    private static boolean isRejectedCredential(HttpStatusCode status) {
        return status.isSameCodeAs(HttpStatus.BAD_REQUEST) || status.isSameCodeAs(HttpStatus.NOT_FOUND);
    }

    /**
     * Recognizes Clerk's per-instance API-keys feature gate.
     *
     * <p>Matched on the stable {@code feature_not_enabled} error code rather than
     * the status alone: a bare {@code 403} also covers credential and edge
     * rejections, which are transient and must stay retryable.
     */
    private static boolean isFeatureDisabled(HttpStatusCodeException clerkRejection) {
        return clerkRejection.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)
                && clerkRejection.getResponseBodyAsString().contains(CLERK_FEATURE_DISABLED_CODE);
    }

    private static boolean isAvailabilityProbeRejection(
            String presentedSecret, HttpStatusCodeException clerkRejection) {
        return CLERK_AVAILABILITY_PROBE_SECRET.equals(presentedSecret)
                && clerkRejection.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND);
    }

    private ApiKeyOperationUnavailableException disabledFeatureException(HttpStatusCodeException clerkRejection) {
        apiKeyFeatureState = ApiKeyFeatureState.DISABLED;
        return new ApiKeyOperationUnavailableException(FEATURE_DISABLED_MESSAGE, clerkRejection);
    }

    private record ClerkApiKeyVerificationRequest(String secret) {}

    private record ClerkApiKeyRevocationRequest(
            @JsonProperty("revocation_reason") String revocationReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClerkApiKeyVerification(String id, String subject, Boolean revoked, Boolean expired) {}

    /** Provider-backed readiness state latched after the first authoritative outcome. */
    private enum ApiKeyFeatureState {
        UNKNOWN,
        ENABLED,
        DISABLED,
        UNAVAILABLE
    }
}
