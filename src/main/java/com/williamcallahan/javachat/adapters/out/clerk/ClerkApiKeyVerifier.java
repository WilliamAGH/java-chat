package com.williamcallahan.javachat.adapters.out.clerk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
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

    private static final String CLERK_VERIFY_ENDPOINT = "https://api.clerk.com/v1/api_keys/verify";
    private static final String CLERK_REVOKE_ENDPOINT = "https://api.clerk.com/v1/api_keys/{apiKeyId}/revoke";
    private static final String CLERK_API_VERSION_HEADER = "Clerk-API-Version";
    private static final String CLERK_API_VERSION = "2026-05-12";
    private static final Duration CLERK_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CLERK_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLERK_VERIFICATION_CACHE_DURATION = Duration.ofSeconds(30);
    private static final int CLERK_VERIFICATION_CACHE_MAXIMUM_ENTRIES = 10_000;
    private static final int CLERK_VERIFICATION_MAXIMUM_CONCURRENCY = 8;
    private static final String CLERK_SECRET_KEY_ENVIRONMENT_VARIABLE = "${CLERK_SECRET_KEY:}";
    private static final String SECRET_KEY_MISSING_MESSAGE =
            "Clerk API key verification requires CLERK_SECRET_KEY to be configured.";

    private final RestClient clerkBackendApi;
    private final String clerkSecretKey;
    private final Cache<String, VerifiedApiKey> verifiedApiKeys = Caffeine.newBuilder()
            .expireAfterWrite(CLERK_VERIFICATION_CACHE_DURATION)
            .maximumSize(CLERK_VERIFICATION_CACHE_MAXIMUM_ENTRIES)
            .build();
    private final Semaphore verificationPermits = new Semaphore(CLERK_VERIFICATION_MAXIMUM_CONCURRENCY, true);

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
     * @return true when a Clerk server credential is configured
     */
    @Override
    public boolean isAvailable() {
        return !clerkSecretKey.isEmpty();
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
        if (!isAvailable()) {
            throw new ApiKeyOperationUnavailableException(SECRET_KEY_MISSING_MESSAGE);
        }
        String presentedSecretDigest = secretDigest(presentedSecret);
        VerifiedApiKey cachedApiKey = verifiedApiKeys.getIfPresent(presentedSecretDigest);
        if (cachedApiKey != null) {
            return Optional.of(cachedApiKey);
        }
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
                return Optional.empty();
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
        VerifiedApiKey verifiedApiKey = new VerifiedApiKey(verification.id(), verification.subject());
        verifiedApiKeys.put(presentedSecretDigest, verifiedApiKey);
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
        if (!isAvailable()) {
            throw new ApiKeyOperationUnavailableException(SECRET_KEY_MISSING_MESSAGE);
        }
        try {
            clerkBackendApi
                    .post()
                    .uri(CLERK_REVOKE_ENDPOINT, apiKeyId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClerkApiKeyRevocationRequest("JavaChat CLI logout"))
                    .retrieve()
                    .toBodilessEntity();
            verifiedApiKeys
                    .asMap()
                    .entrySet()
                    .removeIf(cachedApiKey -> cachedApiKey.getValue().id().equals(apiKeyId));
        } catch (RestClientException revocationFailure) {
            throw new ApiKeyOperationUnavailableException("Clerk API key revocation failed", revocationFailure);
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

    private record ClerkApiKeyVerificationRequest(String secret) {}

    private record ClerkApiKeyRevocationRequest(
            @JsonProperty("revocation_reason") String revocationReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClerkApiKeyVerification(String id, String subject, Boolean revoked, Boolean expired) {}

    private static String secretDigest(String presentedSecret) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(presentedSecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailableDigest) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailableDigest);
        }
    }
}
