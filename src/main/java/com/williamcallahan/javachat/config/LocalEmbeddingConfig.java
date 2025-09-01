package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.LocalHashingEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.local-embedding.enabled", havingValue = "true", matchIfMissing = false)
public class LocalEmbeddingConfig {
    @Bean
    public EmbeddingModel localEmbeddingModel() {
        return new LocalHashingEmbeddingModel(512);
    }
}


