package com.williamcallahan.javachat.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for Qdrant health checking and connection management.
 * Provides fallback when Qdrant is unavailable.
 */
@Configuration
public class QdrantHealthConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(QdrantHealthConfig.class);
    
    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String qdrantHost;
    
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int qdrantPort;
    
    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String qdrantApiKey;
    
    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;
    
    /**
     * Checks if Qdrant is available by attempting to connect.
     */
    public boolean isQdrantAvailable() {
        try {
            logger.info("Checking Qdrant availability at {}:{}", qdrantHost, qdrantPort);
            
            QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, useTls);
            
            if (qdrantApiKey != null && !qdrantApiKey.isEmpty()) {
                grpcClientBuilder.withApiKey(qdrantApiKey);
            }
            
            QdrantGrpcClient grpcClient = grpcClientBuilder.build();
            QdrantClient client = new QdrantClient(grpcClient);
            
            // Try to list collections with a short timeout
            client.listCollectionsAsync()
                .get(3, TimeUnit.SECONDS);
            
            client.close();
            logger.info("Qdrant is available and responding");
            return true;
            
        } catch (Exception e) {
            logger.warn("Qdrant is not available: {}. Application will run with limited vector search functionality.", 
                       e.getMessage());
            return false;
        }
    }
    
    /**
     * Bean to expose Qdrant availability status to other components.
     */
    @Bean
    public QdrantHealthStatus qdrantHealthStatus() {
        boolean available = isQdrantAvailable();
        return new QdrantHealthStatus(available);
    }
    
    /**
     * Simple status holder for Qdrant availability.
     */
    public static class QdrantHealthStatus {
        private final boolean available;
        
        public QdrantHealthStatus(boolean available) {
            this.available = available;
        }
        
        public boolean isAvailable() {
            return available;
        }
    }
}