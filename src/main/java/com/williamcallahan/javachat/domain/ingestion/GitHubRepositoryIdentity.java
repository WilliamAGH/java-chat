package com.williamcallahan.javachat.domain.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents the canonical identity of a GitHub repository.
 *
 * <p>The canonical identity is owner and repository name normalized to lowercase.
 * It is used to derive stable keys, URLs, and collection names so local-path and
 * URL-based ingestion converge on the same repository identity.</p>
 *
 * @param owner repository owner or organization name
 * @param repository repository name
 */
public record GitHubRepositoryIdentity(String owner, String repository) {
    private static final String SEGMENT_ALLOWED_PATTERN = "[a-z0-9-]+";
    private static final String HASH_PREFIX = "-h";
    private static final int HASH_HEX_LENGTH = 8;

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

    /**
     * Returns the canonical Qdrant collection name for this repository.
     */
    public String canonicalCollectionName() {
        return "github-" + encodeCollectionSegment(owner) + "-" + encodeCollectionSegment(repository);
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

    private static String encodeCollectionSegment(String rawSegment) {
        String sanitizedSegment = sanitizeCollectionSegment(rawSegment);
        if (rawSegment.matches(SEGMENT_ALLOWED_PATTERN) && rawSegment.equals(sanitizedSegment)) {
            return sanitizedSegment;
        }
        String hashSuffix = shortHashHex(rawSegment);
        return sanitizedSegment + HASH_PREFIX + hashSuffix;
    }

    private static String sanitizeCollectionSegment(String rawSegment) {
        String normalizedSegment = rawSegment.replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
        String trimmedSegment = normalizedSegment.replaceAll("^-+", "").replaceAll("-+$", "");
        if (trimmedSegment.isBlank()) {
            throw new IllegalArgumentException("Collection segment cannot be blank: " + rawSegment);
        }
        return trimmedSegment;
    }

    private static String shortHashHex(String rawSegment) {
        try {
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = sha256Digest.digest(rawSegment.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexBuilder = new StringBuilder(HASH_HEX_LENGTH * 2);
            for (byte digestByte : digestBytes) {
                hexBuilder.append(String.format("%02x", digestByte));
            }
            return hexBuilder.substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException algorithmException) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable", algorithmException);
        }
    }
}
