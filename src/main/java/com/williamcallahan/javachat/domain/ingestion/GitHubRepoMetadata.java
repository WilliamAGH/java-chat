package com.williamcallahan.javachat.domain.ingestion;

import java.util.Objects;

/**
 * Immutable metadata describing a GitHub repository for source code ingestion.
 *
 * <p>Required fields ({@code repoPath}, {@code repositoryIdentity}, and {@code collectionName})
 * are validated non-blank at construction. Optional fields default to empty string when not
 * provided by the shell script environment.</p>
 *
 * @param repoPath absolute local path to the repository clone
 * @param repositoryIdentity canonical owner/repository identity
 * @param collectionName target canonical Qdrant collection name
 * @param repoBranch branch name at time of indexing
 * @param commitHash HEAD commit SHA for traceability
 * @param license SPDX license identifier parsed from LICENSE file
 * @param repoDescription repository description from GitHub API
 */
public record GitHubRepoMetadata(
        String repoPath,
        GitHubRepositoryIdentity repositoryIdentity,
        String collectionName,
        String repoBranch,
        String commitHash,
        String license,
        String repoDescription) {

    /**
     * Validates required fields and normalizes optional fields to empty string.
     */
    public GitHubRepoMetadata {
        Objects.requireNonNull(repoPath, "repoPath");
        Objects.requireNonNull(repositoryIdentity, "repositoryIdentity");
        Objects.requireNonNull(collectionName, "collectionName");
        if (repoPath.isBlank()) {
            throw new IllegalArgumentException("repoPath must not be blank");
        }
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must not be blank");
        }
        repoBranch = repoBranch == null ? "" : repoBranch;
        commitHash = commitHash == null ? "" : commitHash;
        license = license == null ? "" : license;
        repoDescription = repoDescription == null ? "" : repoDescription;
    }

    /**
     * Returns the canonical repository owner.
     */
    public String repoOwner() {
        return repositoryIdentity.owner();
    }

    /**
     * Returns the canonical repository name.
     */
    public String repoName() {
        return repositoryIdentity.repository();
    }

    /**
     * Returns the canonical owner/repository key.
     */
    public String repoKey() {
        return repositoryIdentity.canonicalRepoKey();
    }

    /**
     * Returns the canonical repository URL.
     */
    public String repoUrl() {
        return repositoryIdentity.canonicalRepoUrl();
    }
}
