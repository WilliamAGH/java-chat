package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Resolves canonical GitHub repository identity from explicit tokens and/or repository URL.
 *
 * <p>This resolver enforces one canonical owner/repository identity so ingestion paths
 * (local clone and GitHub URL) map to the same repository key and collection naming.</p>
 */
@Service
public class GitHubRepositoryIdentityResolver {

    private static final String GITHUB_HOST = "github.com";
    private static final String SSH_PREFIX = "git@github.com:";

    /**
     * Resolves canonical identity using explicit owner/repository values first, then URL parsing.
     *
     * @param owner repository owner token (nullable)
     * @param repository repository name token (nullable)
     * @param repositoryUrl repository URL used as fallback for parsing (nullable)
     * @return canonical repository identity
     */
    public GitHubRepositoryIdentity resolve(String owner, String repository, String repositoryUrl) {
        String normalizedOwner = normalizeNullable(owner);
        String normalizedRepository = normalizeNullable(repository);
        if (!normalizedOwner.isBlank() && !normalizedRepository.isBlank()) {
            return GitHubRepositoryIdentity.of(normalizedOwner, normalizedRepository);
        }

        RepositoryTokens parsedTokens = parseFromRepositoryUrl(repositoryUrl);
        return GitHubRepositoryIdentity.of(parsedTokens.owner(), parsedTokens.repository());
    }

    private RepositoryTokens parseFromRepositoryUrl(String repositoryUrl) {
        String normalizedRepositoryUrl = normalizeNullable(repositoryUrl);
        if (normalizedRepositoryUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Unable to resolve repository identity: owner/repository and URL missing");
        }

        if (normalizedRepositoryUrl.startsWith(SSH_PREFIX)) {
            String repositoryPath = normalizedRepositoryUrl.substring(SSH_PREFIX.length());
            return parseFromRepositoryPath(repositoryPath);
        }

        try {
            URI repositoryUri = new URI(normalizedRepositoryUrl);
            String host = repositoryUri.getHost();
            if (host == null || !GITHUB_HOST.equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("Repository URL must use github.com host: " + repositoryUrl);
            }
            String repositoryPath = repositoryUri.getPath();
            return parseFromRepositoryPath(repositoryPath == null ? "" : repositoryPath);
        } catch (URISyntaxException syntaxException) {
            throw new IllegalArgumentException("Repository URL is not a valid URI: " + repositoryUrl, syntaxException);
        }
    }

    private RepositoryTokens parseFromRepositoryPath(String repositoryPath) {
        String trimmedPath = repositoryPath == null ? "" : repositoryPath.trim();
        if (trimmedPath.startsWith("/")) {
            trimmedPath = trimmedPath.substring(1);
        }
        if (trimmedPath.endsWith("/")) {
            trimmedPath = trimmedPath.substring(0, trimmedPath.length() - 1);
        }
        if (trimmedPath.endsWith(".git")) {
            trimmedPath = trimmedPath.substring(0, trimmedPath.length() - 4);
        }

        String[] pathSegments = trimmedPath.split("/");
        if (pathSegments.length < 2) {
            throw new IllegalArgumentException("Repository URL must include owner and repository path segments");
        }

        String ownerToken = pathSegments[0];
        String repositoryToken = pathSegments[1];
        return new RepositoryTokens(ownerToken, repositoryToken);
    }

    private static String normalizeNullable(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private record RepositoryTokens(String owner, String repository) {
        private RepositoryTokens {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(repository, "repository");
            if (owner.isBlank() || repository.isBlank()) {
                throw new IllegalArgumentException("Repository owner and repository tokens must be present");
            }
        }
    }
}
