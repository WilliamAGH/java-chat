package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.LocalEmbeddingClient;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingClient;
import java.util.Locale;
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
    private static final String REMOTE_EMBEDDING_SERVER_URL_PROPERTY = "app.remote-embedding.server-url";
    private static final String REMOTE_EMBEDDING_API_KEY_PROPERTY = "app.remote-embedding.api-key";
    private static final String OPENAI_EMBEDDING_MODEL_PROPERTY = "spring.ai.openai.embedding.options.model";

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
     * is used. OpenAI embeddings are selected only when {@code OPENAI_API_KEY} and
     * {@code spring.ai.openai.embedding.options.model} are both configured.</p>
     *
     * @param appProperties application configuration (single source of truth for remote embedding)
     * @param openaiApiKey OpenAI API key
     * @param openaiBaseUrl OpenAI base URL
     * @param openaiModel OpenAI embedding model identifier
     * @return embedding client for the selected provider
     * @throws EmbeddingServiceUnavailableException when provider configuration is incomplete
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
        boolean hasRemoteServerUrl = !remoteServerUrl.isBlank();
        boolean hasRemoteApiKey = !remoteApiKey.isBlank();

        if (hasRemoteServerUrl != hasRemoteApiKey) {
            throw new EmbeddingServiceUnavailableException(
                    "Invalid remote embedding configuration: " + REMOTE_EMBEDDING_SERVER_URL_PROPERTY + " and "
                            + REMOTE_EMBEDDING_API_KEY_PROPERTY
                            + " must be configured together.");
        }

        if (hasRemoteServerUrl) {
            rejectGitHubModelsEmbeddingEndpoint(remoteServerUrl, REMOTE_EMBEDDING_SERVER_URL_PROPERTY);
            log.info(
                    "[EMBEDDING] Using remote OpenAI-compatible provider (urlId={})",
                    Integer.toHexString(Objects.hashCode(remoteServerUrl)));
            return OpenAiCompatibleEmbeddingClient.create(
                    remoteServerUrl, remoteApiKey, remoteEmbedding.getModel(), remoteEmbedding.getDimensions());
        }

        String trimmedOpenAiApiKey = openaiApiKey == null ? "" : openaiApiKey.trim();
        if (!trimmedOpenAiApiKey.isEmpty()) {
            rejectGitHubModelsEmbeddingEndpoint(openaiBaseUrl, "spring.ai.openai.embedding.base-url");
            String resolvedOpenAiEmbeddingModel = openaiModel == null ? "" : openaiModel.trim();
            if (resolvedOpenAiEmbeddingModel.isEmpty()) {
                throw new EmbeddingServiceUnavailableException("OpenAI embeddings require "
                        + OPENAI_EMBEDDING_MODEL_PROPERTY
                        + " to be configured when OPENAI_API_KEY is set.");
            }
            log.info("[EMBEDDING] Using OpenAI embeddings provider");
            return OpenAiCompatibleEmbeddingClient.create(
                    openaiBaseUrl,
                    trimmedOpenAiApiKey,
                    resolvedOpenAiEmbeddingModel,
                    appProperties.getEmbeddings().getDimensions());
        }

        throw new EmbeddingServiceUnavailableException(
                "Embedding provider unavailable: no embedding provider configured. "
                        + "Set APP_LOCAL_EMBEDDING_ENABLED=true, configure both "
                        + "REMOTE_EMBEDDING_SERVER_URL and REMOTE_EMBEDDING_API_KEY, or set OPENAI_API_KEY and "
                        + "OPENAI_EMBEDDING_MODEL_NAME.");
    }

    private static void rejectGitHubModelsEmbeddingEndpoint(String configuredBaseUrl, String configurationKey) {
        if (configuredBaseUrl != null
                && configuredBaseUrl.toLowerCase(Locale.ROOT).contains(GITHUB_MODELS_HOST)) {
            throw new EmbeddingServiceUnavailableException("Invalid embedding endpoint in " + configurationKey
                    + ": GitHub Models does not provide embeddings API. Configure an embedding provider that "
                    + "supports /v1/embeddings.");
        }
    }
}
