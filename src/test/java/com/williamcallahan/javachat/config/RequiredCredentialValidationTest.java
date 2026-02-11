package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies fail-fast credential validation for required API keys.
 */
class RequiredCredentialValidationTest {

    @Test
    void bothKeysBlank_throwsIllegalStateException() {
        RequiredCredentialValidation validation = createValidation("", "", false, "");
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredCredentials);
        assertTrue(thrown.getMessage().contains("No LLM API key configured"));
    }

    @Test
    void onlyGithubTokenSet_passes() {
        RequiredCredentialValidation validation = createValidation("ghp_test123", "", false, "");
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void onlyOpenaiApiKeySet_passes() {
        RequiredCredentialValidation validation = createValidation("", "sk-test123", false, "");
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void tlsEnabledWithoutQdrantApiKey_throwsIllegalStateException() {
        RequiredCredentialValidation validation = createValidation("ghp_test123", "", true, "");
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredCredentials);
        assertTrue(thrown.getMessage().contains("QDRANT_API_KEY"));
    }

    @Test
    void tlsEnabledWithQdrantApiKey_passes() {
        RequiredCredentialValidation validation = createValidation("ghp_test123", "", true, "qdrant-key-123");
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    @Test
    void tlsDisabledWithoutQdrantApiKey_passes() {
        RequiredCredentialValidation validation = createValidation("ghp_test123", "", false, "");
        assertDoesNotThrow(validation::validateRequiredCredentials);
    }

    private RequiredCredentialValidation createValidation(
            String githubToken, String openaiApiKey, boolean qdrantTlsEnabled, String qdrantApiKey) {
        return new RequiredCredentialValidation(githubToken, openaiApiKey, qdrantTlsEnabled, qdrantApiKey);
    }
}
