package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies that direct GitHub CLI execution cannot cross environment or embedding-generation boundaries. */
class GitHubRepoProcessorIsolationTest {

    @Test
    void acceptsActiveEnvironmentAndGenerationCollection() {
        assertDoesNotThrow(() -> GitHubRepoProcessor.requireActiveGitHubCollection(
                "local", "github-local-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void rejectsCollectionFromAnotherEnvironment() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireActiveGitHubCollection(
                        "local", "github-prod-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void rejectsCollectionFromAnotherEmbeddingGeneration() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireActiveGitHubCollection(
                        "local", "github-local-qwen3-embedding-8b-4096-openai-java-chat-0123456789abcdef"));
    }
}
