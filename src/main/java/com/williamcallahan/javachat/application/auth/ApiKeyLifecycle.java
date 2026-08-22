package com.williamcallahan.javachat.application.auth;

import java.util.Optional;

/** Defines API-key verification and revocation independently of the external identity provider. */
public interface ApiKeyLifecycle {

    /** Returns whether the deployment can complete key lifecycle operations. */
    boolean isAvailable();

    /**
     * Verifies one presented secret without retaining it.
     *
     * @param presentedSecret opaque API-key secret
     * @return verified non-secret identity, or empty when the provider rejects the key
     */
    Optional<VerifiedApiKey> verify(String presentedSecret);

    /**
     * Revokes the identified key or fails when the provider cannot confirm revocation.
     *
     * @param apiKeyId provider key identifier
     */
    void revoke(String apiKeyId);
}
