package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.GracefulEmbeddingModel;
import com.williamcallahan.javachat.service.LocalEmbeddingModel;
import com.williamcallahan.javachat.service.LocalHashingEmbeddingModel;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingModel;
import java.util.Objects;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive embedding configuration with multiple fallback strategies:
 * 
 * 1. Local embedding server (if enabled and available)
 * 2. OpenAI embedding API (if API key provided)
 * 3. Hash-based fallback (deterministic but not semantic)
 * 4. Graceful degradation (vector search disabled)
 */
@Configuration
public class EmbeddingFallbackConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingFallbackConfig.class);
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 4096;
    
    /**
     * Creates a local embedding model with remote and hash-based fallbacks.
     *
     * @param localUrl local embedding server URL
     * @param localModel local embedding model name
     * @param dimensions embedding dimensions for local model
     * @param useHashFallback whether to fallback to hash embeddings
     * @param remoteUrl remote OpenAI-compatible server URL
     * @param remoteApiKey API key for remote embedding provider
     * @param remoteModel remote embedding model name
     * @param remoteDims remote embedding dimensions
     * @param openaiApiKey OpenAI API key
     * @param openaiBaseUrl OpenAI base URL
     * @param openaiModel OpenAI embedding model name
     * @param restTemplateBuilder RestTemplate builder
     * @return embedding model with fallbacks
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
    public EmbeddingModel localEmbeddingWithFallback(
            @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}") String localUrl,
            @Value("${app.local-embedding.model:text-embedding-qwen3-embedding-8b}") String localModel,
            @Value("${app.local-embedding.dimensions:4096}") int dimensions,
            @Value("${app.local-embedding.use-hash-when-disabled:false}") boolean useHashFallback,
            // Remote OpenAI-compatible provider (e.g., Novita)
            @Value("${app.remote-embedding.server-url:}") String remoteUrl,
            @Value("${app.remote-embedding.api-key:}") String remoteApiKey,
            @Value("${app.remote-embedding.model:text-embedding-3-small}") String remoteModel,
            @Value("${app.remote-embedding.dimensions:4096}") int remoteDims,
            // OpenAI direct fallback (optional)
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com/v1}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openaiModel,
            RestTemplateBuilder restTemplateBuilder) {
        
        log.info("[EMBEDDING] Configuring local embedding with fallback strategies");
        
        // Primary: Local embedding server
        LocalEmbeddingModel primaryModel = new LocalEmbeddingModel(localUrl, localModel, dimensions, restTemplateBuilder);
        
        // Secondary: Prefer remote OpenAI-compatible provider; else OpenAI direct if key present
        EmbeddingModel secondaryModel = null;
        if (remoteUrl != null && !remoteUrl.isBlank() && remoteApiKey != null && !remoteApiKey.isBlank()) {
            log.info("[EMBEDDING] Configured remote OpenAI-compatible embedding fallback (urlId={})",
                Integer.toHexString(Objects.hashCode(remoteUrl)));
            secondaryModel = new OpenAiCompatibleEmbeddingModel(remoteUrl, remoteApiKey, remoteModel,
                    remoteDims > 0 ? remoteDims : dimensions, restTemplateBuilder);
        } else if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            log.info("[EMBEDDING] Configured OpenAI embedding fallback");
            secondaryModel = new OpenAiCompatibleEmbeddingModel(openaiBaseUrl, openaiApiKey, openaiModel,
                    dimensions, restTemplateBuilder);
        } else {
            log.info("[EMBEDDING] No remote/OpenAI embedding fallback configured");
        }
        
        // Tertiary: Hash-based fallback
        LocalHashingEmbeddingModel hashingModel = new LocalHashingEmbeddingModel(dimensions);
        
        return new GracefulEmbeddingModel(primaryModel, secondaryModel, hashingModel, useHashFallback);
    }
    
    /**
     * Creates a remote embedding model with hash-based fallback when local embeddings are disabled.
     *
     * @param remoteUrl remote OpenAI-compatible server URL
     * @param remoteApiKey API key for remote embedding provider
     * @param remoteModel remote embedding model name
     * @param remoteDims remote embedding dimensions
     * @param openaiApiKey OpenAI API key
     * @param openaiBaseUrl OpenAI base URL
     * @param openaiModel OpenAI embedding model name
     * @param useHashFallback whether to fallback to hash embeddings
     * @param restTemplateBuilder RestTemplate builder
     * @return embedding model with fallbacks
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingModel openaiEmbeddingWithFallback(
            // Remote OpenAI-compatible provider (e.g., Novita)
            @Value("${app.remote-embedding.server-url:}") String remoteUrl,
            @Value("${app.remote-embedding.api-key:}") String remoteApiKey,
            @Value("${app.remote-embedding.model:text-embedding-3-small}") String remoteModel,
            @Value("${app.remote-embedding.dimensions:4096}") int remoteDims,
            // OpenAI direct
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com/v1}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openaiModel,
            @Value("${app.local-embedding.use-hash-when-disabled:false}") boolean useHashFallback,
            RestTemplateBuilder restTemplateBuilder) {
        
        log.info("[EMBEDDING] Configuring OpenAI embedding with fallback strategies");
        
        log.info("[EMBEDDING] Configuring remote/OpenAI embeddings with fallback strategies");

        // Primary: Prefer remote provider; else OpenAI direct
        EmbeddingModel primary = null;
        if (remoteUrl != null && !remoteUrl.isBlank() && remoteApiKey != null && !remoteApiKey.isBlank()) {
            log.info("[EMBEDDING] Using remote OpenAI-compatible embedding provider (urlId={})",
                Integer.toHexString(Objects.hashCode(remoteUrl)));
            primary = new OpenAiCompatibleEmbeddingModel(remoteUrl, remoteApiKey, remoteModel,
                    remoteDims > 0 ? remoteDims : DEFAULT_EMBEDDING_DIMENSIONS, restTemplateBuilder);
        } else if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            log.info("[EMBEDDING] Using OpenAI embeddings as primary provider");
            primary = new OpenAiCompatibleEmbeddingModel(openaiBaseUrl, openaiApiKey, openaiModel,
                    DEFAULT_EMBEDDING_DIMENSIONS, restTemplateBuilder);
        }

        LocalHashingEmbeddingModel hashingModel = new LocalHashingEmbeddingModel(DEFAULT_EMBEDDING_DIMENSIONS);

        if (primary != null) {
            return new GracefulEmbeddingModel(primary, hashingModel, useHashFallback);
        }

        log.warn("[EMBEDDING] No remote/OpenAI embedding configured. Falling back to hash-only mode.");
        return useHashFallback ? hashingModel : new NoOpEmbeddingModel();
    }
    
    /**
     * No-op embedding model that always throws exceptions to trigger keyword search fallback
     */
    private static class NoOpEmbeddingModel implements EmbeddingModel {
        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
            throw new GracefulEmbeddingModel.EmbeddingServiceUnavailableException("No embedding service configured");
        }
        
        @Override
        public int dimensions() {
            return DEFAULT_EMBEDDING_DIMENSIONS;
        }
        
        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            throw new GracefulEmbeddingModel.EmbeddingServiceUnavailableException("No embedding service configured");
        }
    }

}
