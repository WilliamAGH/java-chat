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
/**
 * Spring configuration for AI clients and HTTP settings.
 */
@Configuration
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AiConfig {
    private static final long RESPONSE_TIMEOUT_MINUTES = 2;
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    // ChatModel and EmbeddingModel are auto-configured by Spring AI starter
    // using spring.ai.openai.* properties (OpenAI-compatible)
    // CRITICAL: GitHub Models endpoint is https://models.github.ai/inference
    // DO NOT USE: models.inference.ai.azure.com (this is a hallucinated URL)

    /**
     * Creates the AI configuration.
     */
    public AiConfig() {
    }

    /**
     * Builds the chat client for Spring AI.
     *
     * @param chatModel configured chat model bean
     * @return chat client instance
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient chatClient(final ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Configures the shared WebClient builder for AI calls.
     *
     * @return configured WebClient builder
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        // Configure HTTP client with increased timeouts for GitHub Models API
        // GitHub Models can be slower than OpenAI, especially for complex requests
        final HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofMinutes(RESPONSE_TIMEOUT_MINUTES))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
