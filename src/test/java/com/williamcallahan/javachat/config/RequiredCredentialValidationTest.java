package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies fail-fast credential validation for required API keys.
 */
class RequiredCredentialValidationTest {
    private static final String EMPTY_CREDENTIAL = "";
    private static final String GITHUB_TEST_TOKEN = "ghp_test123";
    private static final String OPENAI_TEST_TOKEN = "sk-test123";
    private static final String QDRANT_TEST_API_KEY = "qdrant-key-123";
    private static final String MISSING_LLM_MESSAGE_FRAGMENT = "No LLM API key configured";
    private static final String MISSING_QDRANT_MESSAGE_FRAGMENT = "QDRANT_API_KEY";

    @Test
    void bothKeysBlank_throwsIllegalStateException() {
        RequiredCredentialValidation validation =
                createValidation(EMPTY_CREDENTIAL, EMPTY_CREDENTIAL, false, EMPTY_CREDENTIAL);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredCredentials);
        assertTrue(thrown.getMessage().contains(MISSING_LLM_MESSAGE_FRAGMENT));
    }

    @Test
    void onlyGithubTokenSet_passes() {
        RequiredCredentialValidation validation =
                createValidation(GITHUB_TEST_TOKEN, EMPTY_CREDENTIAL, false, EMPTY_CREDENTIAL);
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void onlyOpenaiApiKeySet_passes() {
        RequiredCredentialValidation validation =
                createValidation(EMPTY_CREDENTIAL, OPENAI_TEST_TOKEN, false, EMPTY_CREDENTIAL);
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void tlsEnabledWithoutQdrantApiKey_throwsIllegalStateException() {
        RequiredCredentialValidation validation =
                createValidation(GITHUB_TEST_TOKEN, EMPTY_CREDENTIAL, true, EMPTY_CREDENTIAL);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredCredentials);
        assertTrue(thrown.getMessage().contains(MISSING_QDRANT_MESSAGE_FRAGMENT));
    }

    @Test
    void tlsEnabledWithQdrantApiKey_passes() {
        RequiredCredentialValidation validation =
                createValidation(GITHUB_TEST_TOKEN, EMPTY_CREDENTIAL, true, QDRANT_TEST_API_KEY);
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void tlsDisabledWithoutQdrantApiKey_passes() {
        RequiredCredentialValidation validation =
                createValidation(GITHUB_TEST_TOKEN, EMPTY_CREDENTIAL, false, EMPTY_CREDENTIAL);
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    private RequiredCredentialValidation createValidation(
            String githubToken, String openaiApiKey, boolean qdrantTlsEnabled, String qdrantApiKey) {
        return new RequiredCredentialValidation(githubToken, openaiApiKey, qdrantTlsEnabled, qdrantApiKey);
    }
}
