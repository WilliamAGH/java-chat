package com.williamcallahan.javachat.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Conditional configuration for Spring AI components.
 * Only enables Spring AI auto-configuration when API keys are available.
 */
@Configuration
public class ConditionalAiConfig {

    /**
     * Creates conditional AI configuration.
     */
    public ConditionalAiConfig() {
    }

    /**
     * Enables Spring AI configuration when at least one API key is non-empty.
     * Uses SpEL expression because @ConditionalOnProperty havingValue does literal
     * string comparison and cannot check for "non-empty" values.
     */
    @Configuration
    @ConditionalOnExpression(
        "!'${spring.ai.openai.api-key:}'.isEmpty() or !'${spring.ai.openai.chat.api-key:}'.isEmpty()"
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
