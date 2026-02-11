package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Validates that required credentials are present at startup.
 *
 * <p>Fails fast with a clear error message instead of allowing the application to start
 * and deferring failure to the first request. Validates:
 * <ul>
 *   <li>At least one LLM API key ({@code GITHUB_TOKEN} or {@code OPENAI_API_KEY}) is configured.</li>
 *   <li>When Qdrant TLS is enabled, a Qdrant API key is present.</li>
 * </ul>
 */
@Configuration
public class RequiredCredentialValidation {
    private static final Logger log = LoggerFactory.getLogger(RequiredCredentialValidation.class);
    private static final String GITHUB_TOKEN_PROPERTY = "${GITHUB_TOKEN:}";
    private static final String OPENAI_API_KEY_PROPERTY = "${OPENAI_API_KEY:}";
    private static final String QDRANT_USE_TLS_PROPERTY = "${spring.ai.vectorstore.qdrant.use-tls:false}";
    private static final String QDRANT_API_KEY_PROPERTY = "${spring.ai.vectorstore.qdrant.api-key:}";
    private static final String MISSING_LLM_API_KEY_MESSAGE =
            "No LLM API key configured. Set GITHUB_TOKEN or OPENAI_API_KEY environment variable.";
    private static final String MISSING_QDRANT_API_KEY_MESSAGE = "Qdrant TLS is enabled but "
            + "spring.ai.vectorstore.qdrant.api-key is not set. "
            + "Set SPRING_AI_VECTORSTORE_QDRANT_API_KEY or QDRANT_API_KEY for authenticated access.";
    private static final String REQUIRED_CREDENTIAL_VALIDATION_PASSED_MESSAGE = "Required credential validation passed";

    private final String githubToken;

    private final String openaiApiKey;

    private final boolean qdrantTlsEnabled;

    private final String qdrantApiKey;

    RequiredCredentialValidation(
            @Value(GITHUB_TOKEN_PROPERTY) String githubToken,
            @Value(OPENAI_API_KEY_PROPERTY) String openaiApiKey,
            @Value(QDRANT_USE_TLS_PROPERTY) boolean qdrantTlsEnabled,
            @Value(QDRANT_API_KEY_PROPERTY) String qdrantApiKey) {
        this.githubToken = githubToken;
        this.openaiApiKey = openaiApiKey;
        this.qdrantTlsEnabled = qdrantTlsEnabled;
        this.qdrantApiKey = qdrantApiKey;
    }

    /**
     * Validates required credentials and halts startup if critical keys are missing.
     *
     * @throws IllegalStateException if no LLM key is configured, or if Qdrant TLS is enabled
     *     without an API key
     */
    @PostConstruct
    public void validateRequiredCredentials() {
        boolean githubTokenPresent = githubToken != null && !githubToken.isBlank();
        boolean openaiKeyPresent = openaiApiKey != null && !openaiApiKey.isBlank();

        if (!githubTokenPresent && !openaiKeyPresent) {
            throw new IllegalStateException(MISSING_LLM_API_KEY_MESSAGE);
        }

        if (qdrantTlsEnabled && (qdrantApiKey == null || qdrantApiKey.isBlank())) {
            throw new IllegalStateException(MISSING_QDRANT_API_KEY_MESSAGE);
        }

        log.info(REQUIRED_CREDENTIAL_VALIDATION_PASSED_MESSAGE);
    }
}
