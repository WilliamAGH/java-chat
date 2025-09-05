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
    
    @Configuration
    @ConditionalOnProperty(
        value = {"spring.ai.openai.api-key", "spring.ai.openai.chat.api-key"},
        matchIfMissing = false,
        havingValue = "!empty"
    )
    @Import({
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class
    })
    public static class EnabledAiConfig {
        // This configuration is only activated when API keys are present
    }
}