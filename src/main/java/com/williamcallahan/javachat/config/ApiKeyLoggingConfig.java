package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ApiKeyLoggingConfig {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyLoggingConfig.class);
    
    @Value("${GITHUB_TOKEN:}")
    private String githubToken;
    
    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;
    
    @Value("${QDRANT_API_KEY:}")
    private String qdrantApiKey;
    
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;
    
    private final Environment environment;
    
    public ApiKeyLoggingConfig(Environment environment) {
        this.environment = environment;
    }
    
    @PostConstruct
    public void logApiKeyStatus() {
        logger.info("=== API Key Configuration Status ===");
        
        boolean isDev = "dev".equalsIgnoreCase(activeProfile) || 
                       "development".equalsIgnoreCase(activeProfile) ||
                       "local".equalsIgnoreCase(activeProfile);
        
        // GitHub Token
        logApiKey("GITHUB_TOKEN", githubToken, isDev);
        
        // OpenAI API Key
        logApiKey("OPENAI_API_KEY", openaiApiKey, isDev);
        
        // Qdrant API Key
        logApiKey("QDRANT_API_KEY", qdrantApiKey, isDev);
        
        // Log which API will be used for chat
        if (hasValue(githubToken)) {
            logger.info("Chat API: Using GitHub Models");
        } else if (hasValue(openaiApiKey)) {
            logger.info("Chat API: Using OpenAI API");
        } else {
            logger.warn("Chat API: No API key configured - chat functionality will not work!");
        }
        
        logger.info("===================================");
    }
    
    private void logApiKey(String keyName, String keyValue, boolean isDev) {
        if (!hasValue(keyValue)) {
            logger.info("{}: Not configured", keyName);
        } else if (isDev) {
            // In dev mode, show last 4 characters
            String masked = maskApiKey(keyValue, 4);
            logger.info("{}: Configured (***{})", keyName, masked);
        } else {
            // In production, only show that it's configured
            logger.info("{}: Configured", keyName);
        }
    }
    
    private boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }
    
    private String maskApiKey(String key, int visibleChars) {
        if (key == null || key.length() <= visibleChars) {
            return "****";
        }
        return key.substring(key.length() - visibleChars);
    }
}