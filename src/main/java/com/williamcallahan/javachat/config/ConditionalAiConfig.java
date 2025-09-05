package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.QdrantHealthConfig.QdrantHealthStatus;
import com.williamcallahan.javachat.vectorstore.InMemoryVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Conditional configuration for Spring AI components.
 * Only enables Spring AI auto-configuration when API keys are available.
 */
@Configuration
public class ConditionalAiConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(ConditionalAiConfig.class);
    
    @Configuration
    @ConditionalOnProperty(
        value = {"spring.ai.openai.api-key", "spring.ai.openai.chat.api-key"},
        matchIfMissing = false,
        havingValue = "!empty"
    )
    @Import({
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class
    })
    public static class EnabledAiConfig {
        // This configuration is only activated when API keys are present
    }
    
    /**
     * Conditionally import Qdrant autoconfiguration only when Qdrant is available.
     */
    @Configuration
    @ConditionalOnBean(QdrantHealthStatus.class)
    public static class VectorStoreConfig {
        
        @Bean
        @Primary
        public VectorStore vectorStore(
                QdrantHealthStatus qdrantHealthStatus,
                EmbeddingModel embeddingModel) {
            
            if (qdrantHealthStatus.isAvailable()) {
                // Qdrant is available, let Spring AI autoconfigure handle it
                logger.info("Qdrant is available - using QdrantVectorStore");
                // Return null here to let the autoconfiguration create the bean
                return null;
            } else {
                // Qdrant is not available, use in-memory fallback
                logger.warn("Qdrant is not available - using InMemoryVectorStore as fallback");
                logger.warn("Note: Vector search functionality will be limited and data will not persist");
                return new InMemoryVectorStore(embeddingModel);
            }
        }
    }
}