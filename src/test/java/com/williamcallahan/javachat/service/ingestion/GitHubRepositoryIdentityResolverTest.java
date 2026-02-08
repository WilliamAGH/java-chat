package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import org.junit.jupiter.api.Test;

/**
 * Verifies canonical GitHub identity resolution from explicit values and URLs.
 */
class GitHubRepositoryIdentityResolverTest {

    private final GitHubRepositoryIdentityResolver repositoryIdentityResolver = new GitHubRepositoryIdentityResolver();

    @Test
    void resolvesFromExplicitOwnerAndRepository() {
        GitHubRepositoryIdentity repositoryIdentity =
                repositoryIdentityResolver.resolve("ExampleOrg", "ExampleRepo", "");

        assertEquals("exampleorg", repositoryIdentity.owner());
        assertEquals("examplerepo", repositoryIdentity.repository());
        assertEquals("exampleorg/examplerepo", repositoryIdentity.canonicalRepoKey());
    }

    @Test
    void resolvesFromSshRepositoryUrlWhenOwnerAndRepositoryMissing() {
        GitHubRepositoryIdentity repositoryIdentity =
                repositoryIdentityResolver.resolve("", "", "git@github.com:OpenAI/Java-Chat.git");

        assertEquals("openai", repositoryIdentity.owner());
        assertEquals("java-chat", repositoryIdentity.repository());
    }

    @Test
    void rejectsNonGithubHost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> repositoryIdentityResolver.resolve("", "", "https://gitlab.com/openai/java-chat"));
    }
}
