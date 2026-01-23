package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Logs API key configuration details at startup for diagnostics.
 */
@Configuration
public class ApiKeyLoggingConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyLoggingConfig.class);
    private static final String DEV_PROFILE = "dev";
    private static final String GITHUB_MODELS_HOST = "models.github.ai";
    private static final int MASK_VISIBLE_CHARS = 4;

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${QDRANT_API_KEY:}")
    private String qdrantApiKey;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${spring.ai.openai.base-url:}")
    private String baseUrl;

    /**
     * Logs API key configuration at startup to aid diagnostics.
     */
    @PostConstruct
    public void logApiKeyStatus() {
        LOGGER.info("=== API Key Configuration Status ===");

        boolean isDev = DEV_PROFILE.equalsIgnoreCase(activeProfile);
        boolean usesGitHubModelsEndpoint = baseUrl != null && baseUrl.contains(GITHUB_MODELS_HOST);

        logApiKey("GITHUB_TOKEN", githubToken, isDev);
        logApiKey("OPENAI_API_KEY", openaiApiKey, isDev);
        logApiKey("QDRANT_API_KEY", qdrantApiKey, isDev);

        if (usesGitHubModelsEndpoint && hasValue(githubToken)) {
            LOGGER.info("Chat API: Using GitHub Models");
        } else if (!usesGitHubModelsEndpoint && hasValue(openaiApiKey)) {
            LOGGER.info("Chat API: Using OpenAI API");
        } else if (hasValue(githubToken)) {
            LOGGER.info("Chat API: Using GitHub Models (fallback)");
        } else if (hasValue(openaiApiKey)) {
            LOGGER.info("Chat API: Using OpenAI API (fallback)");
        } else {
            LOGGER.warn("Chat API: No API key configured - chat functionality will not work!");
        }

        LOGGER.info("===================================");
    }

    private void logApiKey(String keyName, String keyValue, boolean isDev) {
        if (!hasValue(keyValue)) {
            LOGGER.info("{}: Not configured", keyName);
        } else if (isDev) {
            String masked = maskApiKey(keyValue);
            LOGGER.info("{}: Configured (***{})", keyName, masked);
        } else {
            LOGGER.info("{}: Configured", keyName);
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() <= MASK_VISIBLE_CHARS) {
            return "****";
        }
        return key.substring(key.length() - MASK_VISIBLE_CHARS);
    }
}
