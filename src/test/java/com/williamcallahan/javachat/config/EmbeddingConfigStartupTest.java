package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.OpenAiCompatibleEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Verifies deployment-shaped embedding provider configuration during startup.
 */
class EmbeddingConfigStartupTest {
    private static final String TEST_API_KEY = "test-embedding-api-key";
    private static final String TEST_OPENAI_EMBEDDING_MODEL = "provider/test-openai-embedding-model";

    @Test
    void remoteCredentialUsesCanonicalApplicationProperties() {
        try (ConfigurableApplicationContext applicationContext =
                runApplication("--REMOTE_EMBEDDING_API_KEY=" + TEST_API_KEY, "--OPENAI_API_KEY=")) {
            EmbeddingClient embeddingClient = applicationContext.getBean(EmbeddingClient.class);
            AppProperties appProperties = applicationContext.getBean(AppProperties.class);
            Environment applicationEnvironment = applicationContext.getEnvironment();
            RemoteEmbedding remoteEmbedding = appProperties.getRemoteEmbedding();

            assertInstanceOf(OpenAiCompatibleEmbeddingClient.class, embeddingClient);
            assertEquals(
                    applicationEnvironment.getRequiredProperty("app.remote-embedding.server-url"),
                    remoteEmbedding.getServerUrl());
            assertEquals(
                    applicationEnvironment.getRequiredProperty("app.remote-embedding.model"),
                    remoteEmbedding.getModel());
            assertEquals(remoteEmbedding.getModel(), embeddingClient.modelName());
            assertFalse(remoteEmbedding.getServerUrl().isBlank());
            assertTrue(remoteEmbedding.getModel().contains("/"));
        }
    }

    @Test
    void canonicalRemoteEndpointDoesNotOverrideExplicitOpenAiCredential() {
        try (ConfigurableApplicationContext applicationContext = runApplication(
                "--REMOTE_EMBEDDING_API_KEY=",
                "--OPENAI_API_KEY=" + TEST_API_KEY,
                "--app.embeddings.open-ai-model=" + TEST_OPENAI_EMBEDDING_MODEL)) {
            EmbeddingClient embeddingClient = applicationContext.getBean(EmbeddingClient.class);

            assertInstanceOf(OpenAiCompatibleEmbeddingClient.class, embeddingClient);
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
