package com.williamcallahan.javachat.config;

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
    private static final String LOG_OPENAI_NOT_CONFIGURED = "OPENAI_API_KEY: Not configured";
    private static final String LOG_OPENAI_CONFIGURED = "OPENAI_API_KEY: Configured";
    private static final String LOG_OPENAI_CONFIGURED_MASKED = "OPENAI_API_KEY: Configured (masked)";
    private static final String LOG_QDRANT_NOT_CONFIGURED = "QDRANT_API_KEY: Not configured";
    private static final String LOG_QDRANT_CONFIGURED = "QDRANT_API_KEY: Configured";
    private static final String LOG_QDRANT_CONFIGURED_MASKED = "QDRANT_API_KEY: Configured (masked)";
    private static final String LOG_CHAT_OPENAI = "Chat API: Shared LLM gateway selected";
    private static final String LOG_CHAT_OPENAI_CREDENTIAL_MISSING =
            "Chat API: Shared LLM gateway requires OPENAI_API_KEY";
    private static final String OPENAI_API_KEY_PROPERTY = "${OPENAI_API_KEY:}";
    private static final String ACTIVE_PROFILE_PROPERTY = "${spring.profiles.active:dev}";
    private final QdrantConnectionProperties qdrantConnectionProperties;

    @Value(OPENAI_API_KEY_PROPERTY)
    private String openaiApiKey;

    @Value(ACTIVE_PROFILE_PROPERTY)
    private String activeProfile;

    /**
     * Creates startup diagnostics bound to the canonical chat-provider selection.
     *
     * @param qdrantConnectionProperties canonical Qdrant connection settings
     */
    public ApiKeyLoggingConfig(QdrantConnectionProperties qdrantConnectionProperties) {
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
        logOpenAiChatSelection();
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
