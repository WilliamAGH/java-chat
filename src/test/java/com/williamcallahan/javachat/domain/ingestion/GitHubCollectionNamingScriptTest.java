package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shell pipeline that owns canonical GitHub collection naming.
 */
class GitHubCollectionNamingScriptTest {
    private static final String DEV_GENERATION_PREFIX = "github-dev-qwen3-embedding-4b-2560-";

    @Test
    void separatesHyphenatedOwnerAndRepositoryBoundaries() throws IOException, InterruptedException {
        String hyphenatedOwnerCollection = resolveCanonicalCollectionName("https://github.com/a-b/c");
        String hyphenatedRepositoryCollection = resolveCanonicalCollectionName("https://github.com/a/b-c");

        assertEquals(DEV_GENERATION_PREFIX + "a-b_c", hyphenatedOwnerCollection);
        assertEquals(DEV_GENERATION_PREFIX + "a-b-c", hyphenatedRepositoryCollection);
        assertNotEquals(hyphenatedOwnerCollection, hyphenatedRepositoryCollection);
    }

    @Test
    void preservesExistingCollectionNamesForSimpleOwners() throws IOException, InterruptedException {
        assertEquals(
                DEV_GENERATION_PREFIX + "williamagh-apple-maps-java",
                resolveCanonicalCollectionName("https://github.com/williamagh/apple-maps-java"));
        assertEquals(
                DEV_GENERATION_PREFIX + "williamagh-tui4j",
                resolveCanonicalCollectionName("https://github.com/williamagh/tui4j"));
    }

    @Test
    void keepsPunctuationVariantsDistinct() throws IOException, InterruptedException {
        String underscoreRepositoryCollection = resolveCanonicalCollectionName("https://github.com/openai/java_chat");
        String hyphenRepositoryCollection = resolveCanonicalCollectionName("https://github.com/openai/java-chat");

        assertNotEquals(underscoreRepositoryCollection, hyphenRepositoryCollection);
    }

    @Test
    void normalizesRepositoryIdentityCase() throws IOException, InterruptedException {
        assertEquals(
                resolveCanonicalCollectionName("https://github.com/OpenAI/Java-Chat"),
                resolveCanonicalCollectionName("https://github.com/openai/java-chat"));
    }

    @Test
    void acceptsActiveGenerationCollection() throws IOException, InterruptedException {
        ShellInvocation shellInvocation = validateCollectionName(
                DEV_GENERATION_PREFIX + "williamagh-tui4j", "https://github.com/williamagh/tui4j");

        assertEquals(0, shellInvocation.exitCode(), shellInvocation.standardOutput());
    }

    @Test
    void rejectsCollectionOutsideActiveGeneration() throws IOException, InterruptedException {
        ShellInvocation shellInvocation =
                validateCollectionName(DEV_GENERATION_PREFIX + "a-b-c", "https://github.com/a-b/c");

        assertNotEquals(0, shellInvocation.exitCode(), shellInvocation.standardOutput());
        assertTrue(shellInvocation.standardOutput().contains("canonical name '" + DEV_GENERATION_PREFIX + "a-b_c'"));
    }

    private String resolveCanonicalCollectionName(String repositoryUrl) throws IOException, InterruptedException {
        Path identityScriptPath =
                Path.of("scripts", "lib", "github_identity.sh").toAbsolutePath();
        ProcessBuilder shellCommand = new ProcessBuilder(
                "bash",
                "-c",
                "RED=''; NC=''; source \"$GITHUB_IDENTITY_SCRIPT\"; "
                        + "extract_repository_identity \"$GITHUB_REPOSITORY_URL\"; "
                        + "printf '%s' \"$CANONICAL_COLLECTION_NAME\"");
        shellCommand.environment().put("GITHUB_IDENTITY_SCRIPT", identityScriptPath.toString());
        shellCommand.environment().put("GITHUB_REPOSITORY_URL", repositoryUrl);
        shellCommand.environment().put("SPRING_PROFILE", "dev");
        shellCommand.redirectErrorStream(true);

        Process shellProcess = shellCommand.start();
        String scriptStandardOutput = new String(shellProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int scriptExitCode = shellProcess.waitFor();

        assertEquals(0, scriptExitCode, scriptStandardOutput);
        return scriptStandardOutput.trim();
    }

    private ShellInvocation validateCollectionName(String collectionName, String repositoryUrl)
            throws IOException, InterruptedException {
        Path identityScriptPath =
                Path.of("scripts", "lib", "github_identity.sh").toAbsolutePath();
        ProcessBuilder shellCommand = new ProcessBuilder(
                "bash",
                "-c",
                "RED=''; NC=''; source \"$GITHUB_IDENTITY_SCRIPT\"; "
                        + "require_canonical_collection_name \"$GITHUB_COLLECTION_NAME\" \"$GITHUB_REPOSITORY_URL\"");
        shellCommand.environment().put("GITHUB_IDENTITY_SCRIPT", identityScriptPath.toString());
        shellCommand.environment().put("GITHUB_COLLECTION_NAME", collectionName);
        shellCommand.environment().put("GITHUB_REPOSITORY_URL", repositoryUrl);
        shellCommand.environment().put("SPRING_PROFILE", "dev");
        shellCommand.redirectErrorStream(true);

        Process shellProcess = shellCommand.start();
        String scriptStandardOutput = new String(shellProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int scriptExitCode = shellProcess.waitFor();
        return new ShellInvocation(scriptExitCode, scriptStandardOutput.trim());
    }

    private record ShellInvocation(int exitCode, String standardOutput) {}
}
