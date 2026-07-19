package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.LocalEmbeddingClient;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingClient;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Embedding provider configuration with strict error propagation.
 *
 * <p>Providers are selected based on explicit configuration. No runtime fallback is attempted,
 * so provider failures surface immediately and prevent caching invalid embeddings.</p>
 */
@Configuration
public class EmbeddingConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);
    private static final String OPENAI_API_KEY_ENVIRONMENT_VARIABLE = "OPENAI_API_KEY";
    private static final String OPENAI_BASE_URL_ENVIRONMENT_VARIABLE = "OPENAI_BASE_URL";

    /**
     * Creates a local embedding model when local embeddings are enabled.
     *
     * @param appProperties application configuration
     * @param restTemplateBuilder RestTemplate builder
     * @return local embedding model
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingClient.class)
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
    public EmbeddingClient localEmbeddingClient(AppProperties appProperties, RestTemplateBuilder restTemplateBuilder) {
        LocalEmbedding localEmbedding =
                Objects.requireNonNull(appProperties, "appProperties").getLocalEmbedding();
        log.info("[EMBEDDING] Using local embedding provider");
        return new LocalEmbeddingClient(
                localEmbedding.getServerUrl(),
                localEmbedding.getModel(),
                localEmbedding.getDimensions(),
                localEmbedding.getBatchSize(),
                restTemplateBuilder);
    }

    /**
     * Creates an embedding client for the shared OpenAI-compatible gateway.
     *
     * <p>Chat and embeddings intentionally share {@code OPENAI_BASE_URL} and {@code OPENAI_API_KEY}.
     * The embedding model remains independent from the chat-only {@code OPENAI_MODEL}.</p>
     *
     * @param appProperties application configuration (single source of truth for non-secret embedding settings)
     * @param openAiApiKey OpenAI API credential from the environment
     * @param openAiBaseUrl shared gateway base URL from the environment
     * @return embedding client for the configured gateway
     * @throws IllegalStateException when gateway configuration is incomplete
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingClient.class)
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingClient gatewayEmbeddingClient(
            AppProperties appProperties,
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${OPENAI_BASE_URL:}") String openAiBaseUrl) {
        AppProperties.Embeddings embeddings = appProperties.getEmbeddings();
        String trimmedOpenAiApiKey = openAiApiKey == null ? "" : openAiApiKey.trim();
        if (trimmedOpenAiApiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Gateway embeddings require " + OPENAI_API_KEY_ENVIRONMENT_VARIABLE + " to be configured.");
        }
        String trimmedOpenAiBaseUrl = openAiBaseUrl == null ? "" : openAiBaseUrl.trim();
        if (trimmedOpenAiBaseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "Gateway embeddings require " + OPENAI_BASE_URL_ENVIRONMENT_VARIABLE + " to be configured.");
        }
        log.info("[EMBEDDING] Using shared OpenAI-compatible gateway");
        return OpenAiCompatibleEmbeddingClient.create(
                trimmedOpenAiBaseUrl, trimmedOpenAiApiKey, embeddings.getModel(), embeddings.getDimensions());
    }
}
