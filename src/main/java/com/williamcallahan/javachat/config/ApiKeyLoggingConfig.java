package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
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
    private static final String LOG_HEADER = "=== API Key Configuration Status ===";
    private static final String LOG_FOOTER = "===================================";
    private static final String LOG_GITHUB_NOT_CONFIGURED = "GITHUB_TOKEN: Not configured";
    private static final String LOG_GITHUB_CONFIGURED = "GITHUB_TOKEN: Configured";
    private static final String LOG_GITHUB_CONFIGURED_MASKED = "GITHUB_TOKEN: Configured (masked)";
    private static final String LOG_OPENAI_NOT_CONFIGURED = "OPENAI_API_KEY: Not configured";
    private static final String LOG_OPENAI_CONFIGURED = "OPENAI_API_KEY: Configured";
    private static final String LOG_OPENAI_CONFIGURED_MASKED = "OPENAI_API_KEY: Configured (masked)";
    private static final String LOG_QDRANT_NOT_CONFIGURED = "QDRANT_API_KEY: Not configured";
    private static final String LOG_QDRANT_CONFIGURED = "QDRANT_API_KEY: Configured";
    private static final String LOG_QDRANT_CONFIGURED_MASKED = "QDRANT_API_KEY: Configured (masked)";
    private static final String LOG_CHAT_GITHUB = "Chat API: Using GitHub Models";
    private static final String LOG_CHAT_OPENAI = "Chat API: Using OpenAI API";
    private static final String LOG_CHAT_GITHUB_FALLBACK = "Chat API: Using GitHub Models (fallback)";
    private static final String LOG_CHAT_OPENAI_FALLBACK = "Chat API: Using OpenAI API (fallback)";
    private static final String LOG_CHAT_MISSING =
        "Chat API: No API key configured - chat functionality will not work!";
    private static final String GITHUB_TOKEN_PROPERTY = "${GITHUB_TOKEN:}";
    private static final String OPENAI_API_KEY_PROPERTY = "${OPENAI_API_KEY:}";
    private static final String QDRANT_API_KEY_PROPERTY = "${QDRANT_API_KEY:}";
    private static final String ACTIVE_PROFILE_PROPERTY = "${spring.profiles.active:dev}";
    private static final String BASE_URL_PROPERTY = "${spring.ai.openai.base-url:}";

    @Value(GITHUB_TOKEN_PROPERTY)
    private String githubToken;

    @Value(OPENAI_API_KEY_PROPERTY)
    private String openaiApiKey;

    @Value(QDRANT_API_KEY_PROPERTY)
    private String qdrantApiKey;

    @Value(ACTIVE_PROFILE_PROPERTY)
    private String activeProfile;

    @Value(BASE_URL_PROPERTY)
    private String baseUrl;

    /**
     * Logs API key configuration at startup to aid diagnostics.
     */
    @PostConstruct
    public void logApiKeyStatus() {
        String normalizedProfile = activeProfile == null
            ? ""
            : activeProfile.trim().toLowerCase(Locale.ROOT);
        final boolean devProfile = DEV_PROFILE.equals(normalizedProfile);
        final boolean githubModelsEnabled = baseUrl != null && baseUrl.contains(GITHUB_MODELS_HOST);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_HEADER);
            if (!isNonBlank(githubToken)) {
                LOGGER.info(LOG_GITHUB_NOT_CONFIGURED);
            } else if (devProfile) {
                LOGGER.info(LOG_GITHUB_CONFIGURED_MASKED);
            } else {
                LOGGER.info(LOG_GITHUB_CONFIGURED);
            }

            if (!isNonBlank(openaiApiKey)) {
                LOGGER.info(LOG_OPENAI_NOT_CONFIGURED);
            } else if (devProfile) {
                LOGGER.info(LOG_OPENAI_CONFIGURED_MASKED);
            } else {
                LOGGER.info(LOG_OPENAI_CONFIGURED);
            }

            if (!isNonBlank(qdrantApiKey)) {
                LOGGER.info(LOG_QDRANT_NOT_CONFIGURED);
            } else if (devProfile) {
                LOGGER.info(LOG_QDRANT_CONFIGURED_MASKED);
            } else {
                LOGGER.info(LOG_QDRANT_CONFIGURED);
            }
        }

        logChatApiSelection(githubModelsEnabled);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_FOOTER);
        }
    }

    private void logChatApiSelection(final boolean githubModelsEnabled) {
        final boolean githubTokenPresent = isNonBlank(githubToken);
        final boolean openAiTokenPresent = isNonBlank(openaiApiKey);

        if (githubModelsEnabled && githubTokenPresent) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_CHAT_GITHUB);
            }
        } else if (!githubModelsEnabled && openAiTokenPresent) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_CHAT_OPENAI);
            }
        } else if (githubTokenPresent) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_CHAT_GITHUB_FALLBACK);
            }
        } else if (openAiTokenPresent) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_CHAT_OPENAI_FALLBACK);
            }
        } else if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(LOG_CHAT_MISSING);
        }
    }

    private boolean isNonBlank(final String text) {
        return text != null && !text.isBlank();
    }

}
