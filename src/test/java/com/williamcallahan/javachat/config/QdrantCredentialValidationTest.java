package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Lazy;

/**
 * Verifies fail-fast Qdrant credential validation for web and non-web application contexts.
 */
class QdrantCredentialValidationTest {
    private static final String TEST_QDRANT_HOST = "qdrant.test";
    private static final int TEST_QDRANT_GRPC_PORT = 6334;
    private static final String EMPTY_QDRANT_CREDENTIAL = "";
    private static final String QDRANT_TEST_API_KEY = "qdrant-key-123";
    private static final String MISSING_QDRANT_MESSAGE_FRAGMENT = "QDRANT_API_KEY";

    @Test
    void tlsEnabledWithoutQdrantApiKey_throwsIllegalStateException() {
        QdrantCredentialValidation validation = newValidation(true, EMPTY_QDRANT_CREDENTIAL);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredQdrantCredential);

        assertTrue(thrown.getMessage().contains(MISSING_QDRANT_MESSAGE_FRAGMENT));
    }

    @Test
    void tlsEnabledWithQdrantApiKey_passes() {
        QdrantCredentialValidation validation = newValidation(true, QDRANT_TEST_API_KEY);

        assertDoesNotThrow(validation::validateRequiredQdrantCredential);
    }

    @Test
    void tlsDisabledWithoutQdrantApiKey_passes() {
        QdrantCredentialValidation validation = newValidation(false, EMPTY_QDRANT_CREDENTIAL);

        assertDoesNotThrow(validation::validateRequiredQdrantCredential);
    }

    @Test
    void validationRemainsEagerWhenApplicationBeansAreLazy() {
        Lazy lazyConfiguration = QdrantCredentialValidation.class.getAnnotation(Lazy.class);

        assertNotNull(lazyConfiguration);
        assertFalse(lazyConfiguration.value());
    }

    @Test
    void nonWebApplicationValidatesQdrantWithoutCreatingChatValidation() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RequiredCredentialValidation.class,
                        QdrantCredentialValidation.class,
                        QdrantConnectionProperties.class)
                .withPropertyValues(
                        QdrantConnectionProperties.QDRANT_HOST_PROPERTY_NAME + "=" + TEST_QDRANT_HOST,
                        QdrantConnectionProperties.QDRANT_GRPC_PORT_PROPERTY_NAME + "=" + TEST_QDRANT_GRPC_PORT,
                        QdrantConnectionProperties.QDRANT_TLS_PROPERTY_NAME + "=false",
                        QdrantConnectionProperties.QDRANT_API_KEY_ENVIRONMENT_VARIABLE_NAME + "=")
                .run(applicationContext -> {
                    assertEquals(0, applicationContext.getBeanNamesForType(RequiredCredentialValidation.class).length);
                    assertEquals(1, applicationContext.getBeanNamesForType(QdrantCredentialValidation.class).length);
                });
    }

    private QdrantCredentialValidation newValidation(boolean useTls, String apiKey) {
        QdrantConnectionProperties connectionProperties =
                new QdrantConnectionProperties(TEST_QDRANT_HOST, TEST_QDRANT_GRPC_PORT, useTls, apiKey);
        return new QdrantCredentialValidation(connectionProperties);
    }
}
