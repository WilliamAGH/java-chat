package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies canonical GitHub repository identity normalization and derived keys.
 */
class GitHubRepositoryIdentityTest {

    @Test
    void createsCanonicalKeyAndUrl() {
        GitHubRepositoryIdentity repositoryIdentity = GitHubRepositoryIdentity.of("OpenAI", "Java_Chat");

        assertEquals("openai", repositoryIdentity.owner());
        assertEquals("java_chat", repositoryIdentity.repository());
        assertEquals("openai/java_chat", repositoryIdentity.canonicalRepoKey());
        assertEquals("https://github.com/openai/java_chat", repositoryIdentity.canonicalRepoUrl());
    }

    @Test
    void rejectsUnsupportedCharacters() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepositoryIdentity.of("owner", "repo name"));
    }
}
