package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies canonical Qdrant connection property binding and secret-safe diagnostics. */
class QdrantConnectionPropertiesTest {
    private static final String TEST_QDRANT_HOST = "qdrant.example";
    private static final int TEST_QDRANT_GRPC_PORT = 7444;
    private static final String TEST_QDRANT_API_KEY = "test-qdrant-api-key";

    @Test
    void bindsEveryQdrantConnectionSettingThroughCanonicalPropertyNames() {
        new ApplicationContextRunner()
                .withUserConfiguration(QdrantConnectionProperties.class)
                .withPropertyValues(
                        QdrantConnectionProperties.QDRANT_HOST_PROPERTY_NAME + "=" + TEST_QDRANT_HOST,
                        QdrantConnectionProperties.QDRANT_GRPC_PORT_PROPERTY_NAME + "=" + TEST_QDRANT_GRPC_PORT,
                        QdrantConnectionProperties.QDRANT_TLS_PROPERTY_NAME + "=true",
                        QdrantConnectionProperties.QDRANT_API_KEY_ENVIRONMENT_VARIABLE_NAME + "=" + TEST_QDRANT_API_KEY)
                .run(applicationContext -> {
                    QdrantConnectionProperties connectionProperties =
                            applicationContext.getBean(QdrantConnectionProperties.class);

                    assertEquals(TEST_QDRANT_HOST, connectionProperties.host());
                    assertEquals(TEST_QDRANT_GRPC_PORT, connectionProperties.grpcPort());
                    assertTrue(connectionProperties.useTls());
                    assertEquals(TEST_QDRANT_API_KEY, connectionProperties.apiKey());
                    assertFalse(connectionProperties.toString().contains(TEST_QDRANT_API_KEY));
                });
    }
}
