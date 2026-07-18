package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.OpenAiProviderRoutingService;
import com.williamcallahan.javachat.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Lazy;

/**
 * Verifies fail-fast credential validation for required API keys.
 */
class RequiredCredentialValidationTest {
    private static final String MISSING_GITHUB_MODELS_MESSAGE_FRAGMENT = "requires GITHUB_TOKEN";
    private static final String MISSING_OPENAI_MESSAGE_FRAGMENT = "requires OPENAI_API_KEY";

    @Test
    void unavailableGithubModelsProvider_throwsIllegalStateException() {
        RequiredCredentialValidation validation = createValidation(false, RateLimitService.ApiProvider.GITHUB_MODELS);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredChatCredential);
        assertTrue(thrown.getMessage().contains(MISSING_GITHUB_MODELS_MESSAGE_FRAGMENT));
    }

    @Test
    void unavailableOpenAiProvider_throwsIllegalStateException() {
        RequiredCredentialValidation validation = createValidation(false, RateLimitService.ApiProvider.OPENAI);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredChatCredential);
        assertTrue(thrown.getMessage().contains(MISSING_OPENAI_MESSAGE_FRAGMENT));
    }

    @Test
    void availableSelectedProvider_passes() {
        RequiredCredentialValidation validation = createValidation(true, RateLimitService.ApiProvider.OPENAI);
        assertDoesNotThrow(validation::validateRequiredChatCredential);
    }

    @Test
    void validationRemainsEagerWhenApplicationBeansAreLazy() {
        Lazy lazyConfiguration = RequiredCredentialValidation.class.getAnnotation(Lazy.class);

        assertNotNull(lazyConfiguration);
        assertFalse(lazyConfiguration.value());
    }

    @Test
    void validationIsLimitedToWebApplications() {
        ConditionalOnWebApplication webApplicationCondition =
                RequiredCredentialValidation.class.getAnnotation(ConditionalOnWebApplication.class);

        assertNotNull(webApplicationCondition);
        assertTrue(webApplicationCondition.type() == ConditionalOnWebApplication.Type.ANY);
    }

    private RequiredCredentialValidation createValidation(
            boolean streamingAvailable, RateLimitService.ApiProvider configuredProvider) {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        OpenAiProviderRoutingService providerRoutingService = mock(OpenAiProviderRoutingService.class);
        when(streamingService.isAvailable()).thenReturn(streamingAvailable);
        when(providerRoutingService.configuredProvider()).thenReturn(configuredProvider);
        return new RequiredCredentialValidation(streamingService, providerRoutingService);
    }
}
