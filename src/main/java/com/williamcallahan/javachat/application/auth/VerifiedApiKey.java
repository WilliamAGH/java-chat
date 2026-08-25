package com.williamcallahan.javachat.application.auth;

/**
 * Identifies a verified API key without carrying its secret.
 *
 * @param id provider key identifier
 * @param subject owning user subject
 */
public record VerifiedApiKey(String id, String subject) {

    /** Enforces complete identity at the provider boundary. */
    public VerifiedApiKey {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("API key verification returned no key identifier");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("API key verification returned no subject");
        }
    }
}
