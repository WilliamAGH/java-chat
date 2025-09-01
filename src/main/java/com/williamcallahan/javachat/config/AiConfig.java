package com.williamcallahan.javachat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@Configuration
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AiConfig {
    // ChatModel and EmbeddingModel are auto-configured by Spring AI starter
    // using spring.ai.openai.* properties (OpenAI-compatible)
    @Bean
    public ChatClient chatClient(@Lazy ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}

