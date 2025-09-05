package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;


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

    @Value("${spring.ai.openai.base-url:}")
    private String baseUrl;
    
    public ApiKeyLoggingConfig() {
    }
    
    @PostConstruct
    public void logApiKeyStatus() {
        logger.info("=== API Key Configuration Status ===");
        
        boolean isDev = "dev".equalsIgnoreCase(activeProfile);
        
        boolean usingGitHubEndpoint = baseUrl != null && baseUrl.contains("models.github.ai");

        // Log direct environment variables
        logApiKey("GITHUB_TOKEN", githubToken, isDev);
        logApiKey("OPENAI_API_KEY", openaiApiKey, isDev);
        logApiKey("QDRANT_API_KEY", qdrantApiKey, isDev);
        
        // Determine which API will be used based on endpoint and available keys
        if (usingGitHubEndpoint && hasValue(githubToken)) {
            logger.info("Chat API: Using GitHub Models");
        } else if (!usingGitHubEndpoint && hasValue(openaiApiKey)) {
            logger.info("Chat API: Using OpenAI API");
        } else if (hasValue(githubToken)) {
            logger.info("Chat API: Using GitHub Models (fallback)");
        } else if (hasValue(openaiApiKey)) {
            logger.info("Chat API: Using OpenAI API (fallback)");
        } else {
            logger.warn("Chat API: No API key configured - chat functionality will not work!");
        }
        
        logger.info("===================================");
    }
    
    private void logApiKey(String keyName, String keyValue, boolean isDev) {
        if (!hasValue(keyValue)) {
            logger.info("{}: Not configured", keyName);
        } else if (isDev) {
            String masked = maskApiKey(keyValue, 4);
            logger.info("{}: Configured (***{})", keyName, masked);
        } else {
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