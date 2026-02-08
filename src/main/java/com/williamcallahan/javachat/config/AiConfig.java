package com.williamcallahan.javachat.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Spring configuration for AI clients and HTTP settings.
 */
@Configuration
@ConfigurationPropertiesScan(basePackageClasses = AppProperties.class)
public class AiConfig {
    private static final long RESPONSE_TIMEOUT_MINUTES = 2;
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

    // ChatModel is auto-configured by Spring AI starter using spring.ai.openai.* properties.
    // CRITICAL: GitHub Models endpoint is https://models.github.ai/inference
    // DO NOT USE: models.inference.ai.azure.com (this is a hallucinated URL)

    /**
     * Creates the AI configuration.
     */
    public AiConfig() {}

    /**
     * Builds the chat client for Spring AI.
     *
     * @param builder chat client builder
     * @return chat client instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(final ChatClient.Builder builder) {
        return builder.build();
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

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    /**
     * Configures the shared RestClient builder for AI calls.
     *
     * @return configured RestClient builder
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(
                (int) Duration.ofMinutes(RESPONSE_TIMEOUT_MINUTES).toMillis());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
