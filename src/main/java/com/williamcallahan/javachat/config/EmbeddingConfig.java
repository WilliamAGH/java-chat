package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.LocalEmbeddingModel;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingModel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
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

    /**
     * Creates a local embedding model when local embeddings are enabled.
     *
     * @param localUrl local embedding server URL
     * @param localModel local embedding model name
     * @param dimensions embedding dimensions for local model
     * @param restTemplateBuilder RestTemplate builder
     * @return local embedding model
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
    public EmbeddingModel localEmbeddingModel(
            @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}") String localUrl,
            @Value("${app.local-embedding.model:text-embedding-qwen3-embedding-8b}") String localModel,
            @Value("${app.local-embedding.dimensions:4096}") int dimensions,
            RestTemplateBuilder restTemplateBuilder) {
        log.info("[EMBEDDING] Using local embedding provider");
        return new LocalEmbeddingModel(localUrl, localModel, dimensions, restTemplateBuilder);
    }

    /**
     * Creates a remote embedding model when local embeddings are disabled.
     *
     * @param appProperties application configuration for embedding dimensions
     * @param remoteUrl remote OpenAI-compatible server URL
     * @param remoteApiKey API key for remote embedding provider
     * @param remoteModel remote embedding model name
     * @param remoteDims remote embedding dimensions
     * @param openaiApiKey OpenAI API key
     * @param openaiBaseUrl OpenAI base URL
     * @param openaiModel OpenAI embedding model name
     * @return remote embedding model
     * @throws EmbeddingServiceUnavailableException when no embedding provider is configured
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingModel remoteEmbeddingModel(
            AppProperties appProperties,
            @Value("${app.remote-embedding.server-url:}") String remoteUrl,
            @Value("${app.remote-embedding.api-key:}") String remoteApiKey,
            @Value("${app.remote-embedding.model:text-embedding-3-small}") String remoteModel,
            @Value("${app.remote-embedding.dimensions:4096}") int remoteDims,
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openaiModel) {
        int embeddingDimensions = appProperties.getEmbeddings().getDimensions();

        if (remoteUrl != null && !remoteUrl.isBlank() && remoteApiKey != null && !remoteApiKey.isBlank()) {
            log.info(
                    "[EMBEDDING] Using remote OpenAI-compatible provider (urlId={})",
                    Integer.toHexString(Objects.hashCode(remoteUrl)));
            return OpenAiCompatibleEmbeddingModel.create(
                    remoteUrl, remoteApiKey, remoteModel, remoteDims > 0 ? remoteDims : embeddingDimensions);
        }

        if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            log.info("[EMBEDDING] Using OpenAI embeddings provider");
            return OpenAiCompatibleEmbeddingModel.create(openaiBaseUrl, openaiApiKey, openaiModel, embeddingDimensions);
        }

        throw new EmbeddingServiceUnavailableException(
                "Embedding provider unavailable: no embedding provider configured. "
                        + "Set APP_LOCAL_EMBEDDING_ENABLED=true or provide "
                        + "REMOTE_EMBEDDING_API_KEY or OPENAI_API_KEY.");
    }
}
