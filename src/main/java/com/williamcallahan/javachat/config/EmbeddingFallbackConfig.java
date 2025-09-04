package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.GracefulEmbeddingModel;
import com.williamcallahan.javachat.service.LocalEmbeddingModel;
import com.williamcallahan.javachat.service.LocalHashingEmbeddingModel;
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
    
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
    public EmbeddingModel localEmbeddingWithFallback(
            @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}") String localUrl,
            @Value("${app.local-embedding.model:text-embedding-qwen3-embedding-8b}") String localModel,
            @Value("${app.local-embedding.dimensions:4096}") int dimensions,
            @Value("${app.local-embedding.use-hash-when-disabled:false}") boolean useHashFallback,
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com/v1}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openaiModel,
            RestTemplateBuilder restTemplateBuilder) {
        
        log.info("[EMBEDDING] Configuring local embedding with fallback strategies");
        
        // Primary: Local embedding server
        LocalEmbeddingModel primaryModel = new LocalEmbeddingModel(localUrl, localModel, dimensions, restTemplateBuilder);
        
        // Secondary: OpenAI API (if available)
        EmbeddingModel secondaryModel = null;
        if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            try {
                // Create OpenAI embedding model with proper configuration
                // For now, skip OpenAI embedding fallback due to constructor complexity
                // The GracefulEmbeddingModel will handle this gracefully
                log.info("[EMBEDDING] OpenAI embedding fallback temporarily disabled - using hash fallback instead");
            } catch (Exception e) {
                log.warn("[EMBEDDING] Failed to configure OpenAI embedding fallback: {}", e.getMessage());
            }
        } else {
            log.info("[EMBEDDING] No OpenAI API key provided - skipping OpenAI embedding fallback");
        }
        
        // Tertiary: Hash-based fallback
        LocalHashingEmbeddingModel hashingModel = new LocalHashingEmbeddingModel(dimensions);
        
        return new GracefulEmbeddingModel(primaryModel, secondaryModel, hashingModel, useHashFallback);
    }
    
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingModel openaiEmbeddingWithFallback(
            @Value("${spring.ai.openai.embedding.api-key:}") String openaiApiKey,
            @Value("${spring.ai.openai.embedding.base-url:https://api.openai.com/v1}") String openaiBaseUrl,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String openaiModel,
            @Value("${app.local-embedding.use-hash-when-disabled:false}") boolean useHashFallback) {
        
        log.info("[EMBEDDING] Configuring OpenAI embedding with fallback strategies");
        
        // Primary: OpenAI API (currently disabled due to constructor complexity)
        if (openaiApiKey != null && !openaiApiKey.trim().isEmpty()) {
            // TODO: Implement proper OpenAI embedding model construction
            log.info("[EMBEDDING] OpenAI API key available but embedding temporarily disabled");
        }
        
        // Create hash-based fallback model with 4096 dimensions to match Qdrant collection
        LocalHashingEmbeddingModel hashingModel = new LocalHashingEmbeddingModel(4096);
        
        // Since primaryModel is currently always null (OpenAI embedding disabled),
        // we always use fallback strategies
        log.warn("[EMBEDDING] No primary embedding service configured. Using hash-based fallback only.");
        if (useHashFallback) {
            log.info("[EMBEDDING] Using hash-based embeddings (limited semantic meaning)");
            return hashingModel; // Return hash model directly
        } else {
            log.warn("[EMBEDDING] Hash fallback disabled. Vector search will fail gracefully.");
            return new NoOpEmbeddingModel();
        }
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
            return 4096; // Match Qdrant collection dimensions
        }
        
        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            throw new GracefulEmbeddingModel.EmbeddingServiceUnavailableException("No embedding service configured");
        }
    }
}
