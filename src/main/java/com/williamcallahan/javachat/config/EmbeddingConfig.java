package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
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
    private static final String GITHUB_MODELS_HOST = "models.github.ai";

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
     * Creates an embedding client for the configured remote or OpenAI provider.
     *
     * <p>Provider is selected at initialization from {@link AppProperties#getRemoteEmbedding()}.
     * When the remote URL and API key are both configured, the remote OpenAI-compatible provider
     * is used. Otherwise, falls back to the direct OpenAI API when {@code OPENAI_API_KEY} is set.
     * The remote embedding model is the single source of truth for model selection; the OpenAI
     * path uses it as a fallback when no explicit OpenAI model is configured.</p>
     *
     * @param appProperties application configuration (single source of truth for remote embedding)
     * @param openaiApiKey OpenAI API key (fallback provider)
     * @param openaiBaseUrl OpenAI base URL (fallback provider)
     * @param openaiModel OpenAI embedding model override (falls back to remote model when blank)
     * @return embedding client for the selected provider
     * @throws EmbeddingServiceUnavailableException when no embedding provider is configured
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingClient.class)
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingClient remoteEmbeddingClient(
            AppProperties appProperties,
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:}") String openaiModel) {
        RemoteEmbedding remoteEmbedding = appProperties.getRemoteEmbedding();
        String remoteServerUrl = remoteEmbedding.getServerUrl();
        String remoteApiKey = remoteEmbedding.getApiKey();

        if (!remoteServerUrl.isBlank() && !remoteApiKey.isBlank()) {
            rejectGitHubModelsEmbeddingEndpoint(remoteServerUrl, "app.remote-embedding.server-url");
            log.info(
                    "[EMBEDDING] Using remote OpenAI-compatible provider (urlId={})",
                    Integer.toHexString(Objects.hashCode(remoteServerUrl)));
            return OpenAiCompatibleEmbeddingClient.create(
                    remoteServerUrl, remoteApiKey, remoteEmbedding.getModel(), remoteEmbedding.getDimensions());
        }

        if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            rejectGitHubModelsEmbeddingEndpoint(openaiBaseUrl, "spring.ai.openai.embedding.base-url");
            String resolvedEmbeddingModel =
                    (openaiModel != null && !openaiModel.isBlank()) ? openaiModel : remoteEmbedding.getModel();
            log.info("[EMBEDDING] Using OpenAI embeddings provider");
            return OpenAiCompatibleEmbeddingClient.create(
                    openaiBaseUrl,
                    openaiApiKey,
                    resolvedEmbeddingModel,
                    appProperties.getEmbeddings().getDimensions());
        }

        throw new EmbeddingServiceUnavailableException(
                "Embedding provider unavailable: no embedding provider configured. "
                        + "Set APP_LOCAL_EMBEDDING_ENABLED=true or provide "
                        + "REMOTE_EMBEDDING_API_KEY or OPENAI_API_KEY.");
    }

    private static void rejectGitHubModelsEmbeddingEndpoint(String configuredBaseUrl, String configurationKey) {
        if (configuredBaseUrl != null && configuredBaseUrl.toLowerCase().contains(GITHUB_MODELS_HOST)) {
            throw new EmbeddingServiceUnavailableException("Invalid embedding endpoint in " + configurationKey
                    + ": GitHub Models does not provide embeddings API. Configure an embedding provider that "
                    + "supports /v1/embeddings.");
        }
    }
}
