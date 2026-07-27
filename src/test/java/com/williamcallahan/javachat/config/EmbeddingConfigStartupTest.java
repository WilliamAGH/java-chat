package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Verifies deployment-shaped embedding provider configuration during startup.
 */
class EmbeddingConfigStartupTest {
    private static final String TEST_API_KEY = "test-embedding-api-key";
    private static final String TEST_EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";
    private static final int TEST_EMBEDDING_DIMENSIONS = 2_560;
    private static final int TEST_LIVE_MAX_CONCURRENT_REQUESTS = 4;
    private static final int TEST_BATCH_MAX_CONCURRENT_REQUESTS = 1;
    private static final double TEST_LIVE_REQUESTS_PER_SECOND = 3.0;
    private static final double TEST_BATCH_REQUESTS_PER_SECOND = 1.0;

    @Test
    void sharedGatewayCredentialUsesEmbeddingOwnedApplicationProperties() {
        try (ConfigurableApplicationContext applicationContext = runApplication(
                "--OPENAI_API_KEY=" + TEST_API_KEY,
                "--OPENAI_BASE_URL=https://gateway.example/v1",
                "--OPENAI_MODEL=chat-only-model",
                "--app.embeddings.model=" + TEST_EMBEDDING_MODEL,
                "--app.embeddings.dimensions=" + TEST_EMBEDDING_DIMENSIONS)) {
            EmbeddingClient embeddingClient = applicationContext.getBean(EmbeddingClient.class);
            AppProperties appProperties = applicationContext.getBean(AppProperties.class);

            assertInstanceOf(OpenAiCompatibleEmbeddingClient.class, embeddingClient);
            assertEquals(TEST_EMBEDDING_MODEL, appProperties.getEmbeddings().getModel());
            assertEquals(
                    TEST_EMBEDDING_DIMENSIONS, appProperties.getEmbeddings().getDimensions());
            assertEquals(
                    TEST_LIVE_MAX_CONCURRENT_REQUESTS,
                    appProperties.getEmbeddings().getLiveMaxConcurrentRequests());
            assertEquals(
                    TEST_BATCH_MAX_CONCURRENT_REQUESTS,
                    appProperties.getEmbeddings().getBatchMaxConcurrentRequests());
            assertEquals(
                    TEST_LIVE_REQUESTS_PER_SECOND, appProperties.getEmbeddings().getLiveRequestsPerSecond());
            assertEquals(
                    TEST_BATCH_REQUESTS_PER_SECOND,
                    appProperties.getEmbeddings().getBatchRequestsPerSecond());
            assertEquals(TEST_EMBEDDING_MODEL, embeddingClient.modelName());
        }
    }

    @Test
    void openAiChatModelDoesNotOverrideEmbeddingModel() {
        try (ConfigurableApplicationContext applicationContext = runApplication(
                "--OPENAI_API_KEY=" + TEST_API_KEY,
                "--OPENAI_BASE_URL=https://gateway.example/v1",
                "--OPENAI_MODEL=chat-only-model",
                "--app.embeddings.model=" + TEST_EMBEDDING_MODEL)) {
            EmbeddingClient embeddingClient = applicationContext.getBean(EmbeddingClient.class);

            assertInstanceOf(OpenAiCompatibleEmbeddingClient.class, embeddingClient);
            assertEquals(TEST_EMBEDDING_MODEL, embeddingClient.modelName());
        }
    }

    private static ConfigurableApplicationContext runApplication(String... applicationArguments) {
        SpringApplication application = new SpringApplication(EmbeddingStartupConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLazyInitialization(false);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application.run(applicationArguments);
    }

    /** Supplies the exact configuration graph needed to verify provider startup. */
    @Configuration(proxyBeanMethods = false)
    @Import(EmbeddingConfig.class)
    @EnableConfigurationProperties(AppProperties.class)
    static class EmbeddingStartupConfiguration {}
}
