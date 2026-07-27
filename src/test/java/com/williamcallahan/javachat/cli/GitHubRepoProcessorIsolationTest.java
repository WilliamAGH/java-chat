package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies that direct GitHub CLI execution cannot cross embedding-generation boundaries. */
class GitHubRepoProcessorIsolationTest {

    @Test
    void acceptsSharedGenerationCollection() {
        assertDoesNotThrow(() -> GitHubRepoProcessor.requireGenerationGitHubCollection(
                "github-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void rejectsEnvironmentScopedCollection() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireGenerationGitHubCollection(
                        "github-prod-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void rejectsCollectionFromAnotherEmbeddingGeneration() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireGenerationGitHubCollection(
                        "github-qwen3-embedding-8b-4096-openai-java-chat-0123456789abcdef"));
    }
}
