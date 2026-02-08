package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies canonical GitHub repository identity normalization and derived keys.
 */
class GitHubRepositoryIdentityTest {

    @Test
    void createsCanonicalKeyUrlAndCollectionName() {
        GitHubRepositoryIdentity repositoryIdentity = GitHubRepositoryIdentity.of("OpenAI", "Java_Chat");

        assertEquals("openai", repositoryIdentity.owner());
        assertEquals("java_chat", repositoryIdentity.repository());
        assertEquals("openai/java_chat", repositoryIdentity.canonicalRepoKey());
        assertEquals("https://github.com/openai/java_chat", repositoryIdentity.canonicalRepoUrl());
        assertTrue(repositoryIdentity.canonicalCollectionName().startsWith("github-openai-java-chat-h"));
    }

    @Test
    void avoidsCollectionNameCollisionBetweenUnderscoreAndHyphenRepositoryNames() {
        GitHubRepositoryIdentity underscoreIdentity = GitHubRepositoryIdentity.of("openai", "java_chat");
        GitHubRepositoryIdentity hyphenIdentity = GitHubRepositoryIdentity.of("openai", "java-chat");

        assertNotEquals(underscoreIdentity.canonicalCollectionName(), hyphenIdentity.canonicalCollectionName());
        assertEquals("github-openai-java-chat", hyphenIdentity.canonicalCollectionName());
    }

    @Test
    void rejectsUnsupportedCharacters() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRepositoryIdentity.of("owner", "repo name"));
    }
}
