package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.config.QdrantHealthConfig.QdrantHealthStatus;
import com.williamcallahan.javachat.vectorstore.InMemoryVectorStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for VectorStore with fallback to in-memory when Qdrant is unavailable.
 */
@Configuration
public class VectorStoreConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(VectorStoreConfig.class);
    
    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String qdrantHost;
    
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int qdrantPort;
    
    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String qdrantApiKey;
    
    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;
    
    @Value("${spring.ai.vectorstore.qdrant.collection-name:java-chat}")
    private String collectionName;
    
    /**
     * Create a VectorStore bean that uses Qdrant if available, or falls back to in-memory.
     */
    @Bean
    @Primary
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore vectorStore(
            QdrantHealthStatus qdrantHealthStatus,
            EmbeddingModel embeddingModel) {
        
        if (qdrantHealthStatus.isAvailable()) {
            logger.info("Qdrant is available - using QdrantVectorStore");
            
            try {
                // Create Qdrant client
                QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, useTls);
                
                if (qdrantApiKey != null && !qdrantApiKey.isEmpty()) {
                    grpcClientBuilder.withApiKey(qdrantApiKey);
                }
                
                QdrantGrpcClient grpcClient = grpcClientBuilder.build();
                QdrantClient qdrantClient = new QdrantClient(grpcClient);
                
                // Create QdrantVectorStore
                QdrantVectorStore.Builder builder = QdrantVectorStore.builder(qdrantClient, embeddingModel)
                    .collectionName(collectionName)
                    .initializeSchema(true);
                
                return builder.build();
                
            } catch (Exception e) {
                logger.error("Failed to create QdrantVectorStore despite health check passing", e);
                logger.warn("Falling back to InMemoryVectorStore");
                return new InMemoryVectorStore(embeddingModel);
            }
            
        } else {
            logger.warn("Qdrant is not available - using InMemoryVectorStore as fallback");
            logger.warn("Note: Vector search functionality will be limited and data will not persist");
            return new InMemoryVectorStore(embeddingModel);
        }
    }
    
    /**
     * Fallback VectorStore bean when no embedding model is available.
     */
    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore fallbackVectorStore() {
        logger.warn("No embedding model available - creating dummy InMemoryVectorStore");
        return new InMemoryVectorStore(null);
    }
}