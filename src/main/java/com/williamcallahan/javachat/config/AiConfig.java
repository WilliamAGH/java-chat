package com.williamcallahan.javachat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AiConfig {
    // ChatModel and EmbeddingModel are auto-configured by Spring AI starter
    // using spring.ai.openai.* properties (OpenAI-compatible)
    // CRITICAL: GitHub Models endpoint is https://models.github.ai/inference
    // DO NOT USE: models.inference.ai.azure.com (this is a hallucinated URL)
    
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient chatClient(@Autowired(required = false) ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }
        return ChatClient.builder(chatModel).build();
    }
    
    @Bean
    public WebClient.Builder webClientBuilder() {
        // Configure HTTP client with increased timeouts for GitHub Models API
        // GitHub Models can be slower than OpenAI, especially for complex requests
        HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(120)) // 2 minutes for response
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000); // 30 seconds for connection
            
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}

