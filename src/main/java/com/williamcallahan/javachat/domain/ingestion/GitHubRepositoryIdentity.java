package com.williamcallahan.javachat.domain.ingestion;

import java.util.Locale;
import java.util.Objects;

/**
 * Represents the canonical identity of a GitHub repository.
 *
 * <p>The canonical identity is owner and repository name normalized to lowercase.
 * It is used to derive stable keys and URLs so local-path and URL-based ingestion
 * converge on the same repository identity.</p>
 *
 * @param owner repository owner or organization name
 * @param repository repository name
 */
public record GitHubRepositoryIdentity(String owner, String repository) {
    /**
     * Validates and normalizes owner/repository tokens.
     */
    public GitHubRepositoryIdentity {
        owner = normalizeToken(owner, "owner");
        repository = normalizeToken(repository, "repository");
    }

    /**
     * Creates a canonical repository identity from owner and repository tokens.
     *
     * @param owner repository owner
     * @param repository repository name
     * @return canonical identity
     */
    public static GitHubRepositoryIdentity of(String owner, String repository) {
        return new GitHubRepositoryIdentity(owner, repository);
    }

    /**
     * Returns the canonical owner/repository key.
     */
    public String canonicalRepoKey() {
        return owner + "/" + repository;
    }

    /**
     * Returns the canonical HTTPS repository URL.
     */
    public String canonicalRepoUrl() {
        return "https://github.com/" + canonicalRepoKey();
    }

    private static String normalizeToken(String rawToken, String tokenName) {
        Objects.requireNonNull(rawToken, tokenName);
        String normalizedToken = rawToken.trim().toLowerCase(Locale.ROOT);
        if (normalizedToken.isBlank()) {
            throw new IllegalArgumentException(tokenName + " must not be blank");
        }
        if (!normalizedToken.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException(tokenName + " contains unsupported characters: " + rawToken);
        }
        return normalizedToken;
    }
}
