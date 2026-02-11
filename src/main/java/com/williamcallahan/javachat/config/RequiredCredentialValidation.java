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

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean qdrantTlsEnabled;

    @Value("${QDRANT_API_KEY:}")
    private String qdrantApiKey;

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
            throw new IllegalStateException(
                    "No LLM API key configured. Set GITHUB_TOKEN or OPENAI_API_KEY environment variable.");
        }

        if (qdrantTlsEnabled && (qdrantApiKey == null || qdrantApiKey.isBlank())) {
            throw new IllegalStateException("Qdrant TLS is enabled but QDRANT_API_KEY is not set. "
                    + "Cloud Qdrant requires an API key for authenticated access.");
        }

        log.info("Required credential validation passed");
    }
}
