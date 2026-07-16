package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.OpenAiProviderRoutingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Validates that required credentials are present at startup.
 *
 * <p>Fails fast with a clear error message instead of allowing the application to start
 * and deferring failure to the first request. Validates:
 * <ul>
 *   <li>The API key required by the selected LLM provider is configured.</li>
 *   <li>When Qdrant TLS is enabled, a Qdrant API key is present.</li>
 * </ul>
 */
@Configuration
@Lazy(false)
public class RequiredCredentialValidation {
    private static final Logger log = LoggerFactory.getLogger(RequiredCredentialValidation.class);
    private static final String QDRANT_USE_TLS_PROPERTY = "${spring.ai.vectorstore.qdrant.use-tls:false}";
    private static final String QDRANT_API_KEY_PROPERTY = "${spring.ai.vectorstore.qdrant.api-key:}";
    private static final String MISSING_GITHUB_MODELS_API_KEY_MESSAGE =
            "Selected LLM provider github_models requires GITHUB_TOKEN.";
    private static final String MISSING_OPENAI_API_KEY_MESSAGE =
            "Selected LLM provider openai requires OPENAI_API_KEY.";
    private static final String MISSING_QDRANT_API_KEY_MESSAGE = "Qdrant TLS is enabled but "
            + "spring.ai.vectorstore.qdrant.api-key is not set. "
            + "Set SPRING_AI_VECTORSTORE_QDRANT_API_KEY or QDRANT_API_KEY for authenticated access.";
    private static final String REQUIRED_CREDENTIAL_VALIDATION_PASSED_MESSAGE = "Required credential validation passed";

    private final OpenAIStreamingService streamingService;
    private final OpenAiProviderRoutingService providerRoutingService;

    private final boolean qdrantTlsEnabled;

    private final String qdrantApiKey;

    RequiredCredentialValidation(
            OpenAIStreamingService streamingService,
            OpenAiProviderRoutingService providerRoutingService,
            @Value(QDRANT_USE_TLS_PROPERTY) boolean qdrantTlsEnabled,
            @Value(QDRANT_API_KEY_PROPERTY) String qdrantApiKey) {
        this.streamingService = streamingService;
        this.providerRoutingService = providerRoutingService;
        this.qdrantTlsEnabled = qdrantTlsEnabled;
        this.qdrantApiKey = qdrantApiKey;
    }

    /**
     * Validates required credentials and halts startup if critical keys are missing.
     *
     * @throws IllegalStateException if the selected LLM provider has no matching key, or if
     *     Qdrant TLS is enabled without an API key
     */
    @PostConstruct
    public void validateRequiredCredentials() {
        if (!streamingService.isAvailable()) {
            throw new IllegalStateException(missingSelectedProviderCredentialMessage());
        }

        if (qdrantTlsEnabled && (qdrantApiKey == null || qdrantApiKey.isBlank())) {
            throw new IllegalStateException(MISSING_QDRANT_API_KEY_MESSAGE);
        }

        log.info(REQUIRED_CREDENTIAL_VALIDATION_PASSED_MESSAGE);
    }

    private String missingSelectedProviderCredentialMessage() {
        return switch (providerRoutingService.configuredProvider()) {
            case GITHUB_MODELS -> MISSING_GITHUB_MODELS_API_KEY_MESSAGE;
            case OPENAI -> MISSING_OPENAI_API_KEY_MESSAGE;
            case LOCAL -> throw new IllegalStateException("Chat API configuration does not support the local provider");
        };
    }
}
