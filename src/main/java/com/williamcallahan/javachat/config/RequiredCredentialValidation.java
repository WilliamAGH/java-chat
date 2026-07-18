package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.OpenAiProviderRoutingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Validates that the selected chat provider credential is present for web applications.
 *
 * <p>Fails fast with a clear error message instead of allowing the application to start
 * and deferring failure to the first chat request. CLI applications do not create the
 * chat streaming service and therefore do not require a chat provider credential.</p>
 */
@Configuration
@Lazy(false)
@ConditionalOnWebApplication
public class RequiredCredentialValidation {
    private static final Logger log = LoggerFactory.getLogger(RequiredCredentialValidation.class);
    private static final String MISSING_GITHUB_MODELS_API_KEY_MESSAGE =
            "Selected LLM provider github_models requires GITHUB_TOKEN.";
    private static final String MISSING_OPENAI_API_KEY_MESSAGE =
            "Selected LLM provider openai requires OPENAI_API_KEY.";
    private static final String CHAT_CREDENTIAL_VALIDATION_PASSED_MESSAGE =
            "Required chat credential validation passed";

    private final OpenAIStreamingService streamingService;
    private final OpenAiProviderRoutingService providerRoutingService;

    RequiredCredentialValidation(
            OpenAIStreamingService streamingService, OpenAiProviderRoutingService providerRoutingService) {
        this.streamingService = streamingService;
        this.providerRoutingService = providerRoutingService;
    }

    /**
     * Validates the selected chat provider credential and halts web startup when it is missing.
     *
     * @throws IllegalStateException if the selected LLM provider has no matching key
     */
    @PostConstruct
    public void validateRequiredChatCredential() {
        if (!streamingService.isAvailable()) {
            throw new IllegalStateException(missingSelectedProviderCredentialMessage());
        }

        log.info(CHAT_CREDENTIAL_VALIDATION_PASSED_MESSAGE);
    }

    private String missingSelectedProviderCredentialMessage() {
        return switch (providerRoutingService.configuredProvider()) {
            case GITHUB_MODELS -> MISSING_GITHUB_MODELS_API_KEY_MESSAGE;
            case OPENAI -> MISSING_OPENAI_API_KEY_MESSAGE;
            case LOCAL -> throw new IllegalStateException("Chat API configuration does not support the local provider");
        };
    }
}
