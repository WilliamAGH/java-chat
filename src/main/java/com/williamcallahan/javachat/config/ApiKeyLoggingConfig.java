package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.OpenAiProviderRoutingService;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import jakarta.annotation.PostConstruct;
import java.util.Objects;
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
    private static final String LOG_CHAT_GITHUB = "Chat API: Selected provider is GitHub Models";
    private static final String LOG_CHAT_GITHUB_CREDENTIAL_MISSING =
            "Chat API: Selected GitHub Models provider requires GITHUB_TOKEN";
    private static final String LOG_CHAT_OPENAI = "Chat API: Selected provider is OpenAI API";
    private static final String LOG_CHAT_OPENAI_CREDENTIAL_MISSING =
            "Chat API: Selected OpenAI provider requires OPENAI_API_KEY";
    private static final String GITHUB_TOKEN_PROPERTY = "${GITHUB_TOKEN:}";
    private static final String OPENAI_API_KEY_PROPERTY = "${OPENAI_API_KEY:}";
    private static final String ACTIVE_PROFILE_PROPERTY = "${spring.profiles.active:dev}";
    private final OpenAiProviderRoutingService providerRoutingService;
    private final QdrantConnectionProperties qdrantConnectionProperties;

    @Value(GITHUB_TOKEN_PROPERTY)
    private String githubToken;

    @Value(OPENAI_API_KEY_PROPERTY)
    private String openaiApiKey;

    @Value(ACTIVE_PROFILE_PROPERTY)
    private String activeProfile;

    /**
     * Creates startup diagnostics bound to the canonical chat-provider selection.
     *
     * @param providerRoutingService validated chat-provider configuration
     * @param qdrantConnectionProperties canonical Qdrant connection settings
     */
    public ApiKeyLoggingConfig(
            OpenAiProviderRoutingService providerRoutingService,
            QdrantConnectionProperties qdrantConnectionProperties) {
        this.providerRoutingService = Objects.requireNonNull(providerRoutingService, "providerRoutingService");
        this.qdrantConnectionProperties =
                Objects.requireNonNull(qdrantConnectionProperties, "qdrantConnectionProperties");
    }

    /**
     * Logs API key configuration at startup to aid diagnostics.
     */
    @PostConstruct
    public void logApiKeyStatus() {
        String normalizedProfile = AsciiTextNormalizer.toLowerAscii(activeProfile == null ? "" : activeProfile.trim());
        final boolean devProfile = DEV_PROFILE.equals(normalizedProfile);

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

            if (!isNonBlank(qdrantConnectionProperties.apiKey())) {
                LOGGER.info(LOG_QDRANT_NOT_CONFIGURED);
            } else if (devProfile) {
                LOGGER.info(LOG_QDRANT_CONFIGURED_MASKED);
            } else {
                LOGGER.info(LOG_QDRANT_CONFIGURED);
            }
        }

        logChatApiSelection();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_FOOTER);
        }
    }

    private void logChatApiSelection() {
        switch (providerRoutingService.configuredProvider()) {
            case GITHUB_MODELS -> logGitHubModelsChatSelection();
            case OPENAI -> logOpenAiChatSelection();
            case LOCAL -> throw new IllegalStateException("Chat API configuration does not support the local provider");
        }
    }

    private void logGitHubModelsChatSelection() {
        if (!isNonBlank(githubToken)) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(LOG_CHAT_GITHUB_CREDENTIAL_MISSING);
            }
            return;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_CHAT_GITHUB);
        }
    }

    private void logOpenAiChatSelection() {
        if (!isNonBlank(openaiApiKey)) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(LOG_CHAT_OPENAI_CREDENTIAL_MISSING);
            }
            return;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_CHAT_OPENAI);
        }
    }

    private boolean isNonBlank(final String text) {
        return text != null && !text.isBlank();
    }
}
