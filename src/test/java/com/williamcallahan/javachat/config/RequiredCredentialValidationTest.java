package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.OpenAIStreamingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Lazy;

/**
 * Verifies fail-fast credential validation for required API keys.
 */
class RequiredCredentialValidationTest {
    private static final String MISSING_OPENAI_MESSAGE_FRAGMENT = "requires OPENAI_API_KEY";

    @Test
    void unavailableSharedGateway_throwsIllegalStateException() {
        RequiredCredentialValidation validation = createValidation(false);
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredChatCredential);
        assertTrue(thrown.getMessage().contains(MISSING_OPENAI_MESSAGE_FRAGMENT));
    }

    @Test
    void availableSelectedProvider_passes() {
        RequiredCredentialValidation validation = createValidation(true);
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

    private RequiredCredentialValidation createValidation(boolean streamingAvailable) {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.isAvailable()).thenReturn(streamingAvailable);
        return new RequiredCredentialValidation(streamingService);
    }
}
