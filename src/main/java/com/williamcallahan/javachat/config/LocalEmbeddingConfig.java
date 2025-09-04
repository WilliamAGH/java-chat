package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.LocalEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
public class LocalEmbeddingConfig {

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder();
    }
    
    @Bean
    public EmbeddingModel localEmbeddingModel(
            @Value("${app.local-embedding.server-url:http://127.0.0.1:8088}") String baseUrl,
            @Value("${app.local-embedding.model:text-embedding-qwen3-embedding-8b}") String modelName,
            @Value("${app.local-embedding.dimensions:4096}") int dimensions,
            RestTemplateBuilder restTemplateBuilder) {
        // LocalEmbeddingModel now handles server unavailability gracefully
        return new LocalEmbeddingModel(baseUrl, modelName, dimensions, restTemplateBuilder);
    }
}


