package com.williamcallahan.javachat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Conditional configuration for Spring AI components.
 * Only enables Spring AI auto-configuration when API keys are available.
 */
@Configuration
public class ConditionalAiConfig {

    private static final String OPENAI_API_KEY = "spring.ai.openai.api-key";
    private static final String OPENAI_CHAT_KEY = "spring.ai.openai.chat.api-key";
    private static final String NON_EMPTY_FLAG = "!empty";

    /**
     * Creates conditional AI configuration.
     */
    public ConditionalAiConfig() {
    }

    /**
     * Enables Spring AI configuration when API keys are available.
     */
    @Configuration
    @ConditionalOnProperty(
        value = {OPENAI_API_KEY, OPENAI_CHAT_KEY},
        matchIfMissing = false,
        havingValue = NON_EMPTY_FLAG
    )
    @Import({
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class
    })
    public static class EnabledAiConfig {
        /**
         * Creates enabled AI configuration.
         */
        public EnabledAiConfig() {
        }
    }
}
