package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies app property validation for newly added retrieval and embedding settings.
 */
class AppPropertiesValidationTest {
    private static final double TEST_LLM_TEMPERATURE = 0.4;
    private static final double TEST_RERANKER_TEMPERATURE = 0.2;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final int TEST_ENRICHMENT_OUTPUT_TOKEN_BUDGET = 640;
    private static final int TEST_RERANKER_OUTPUT_TOKEN_BUDGET = 256;
    private static final long TEST_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 120L;
    private static final long MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 1L;
    private static final long MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 86_400L;
    private static final String TEST_OPENAI_EMBEDDING_BASE_URL = "https://api.openai.com";
    private static final String TEST_REMOTE_EMBEDDING_MODEL = "provider/test-embedding-model";
    private static final int TEST_REMOTE_EMBEDDING_DIMENSIONS = 8;

    @Test
    void rejectsNonPositiveRrfK() {
        AppProperties appProperties = validAppProperties();
        appProperties.getQdrant().setRrfK(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsNonPositiveLocalEmbeddingBatchSize() {
        AppProperties appProperties = validAppProperties();
        appProperties.getLocalEmbedding().setBatchSize(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsNonPositiveCompletionOutputTokenBudget() {
        AppProperties appProperties = validAppProperties();
        appProperties.getLlm().setCompletionOutputTokenBudget(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsNonPositiveEnrichmentOutputTokenBudget() {
        AppProperties appProperties = validAppProperties();
        appProperties.getLlm().setEnrichmentOutputTokenBudget(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsNonPositiveConfiguredProviderBackoff() {
        AppProperties.Llm llmProperties = new AppProperties.Llm();
        llmProperties.setConfiguredProviderBackoffSeconds(0);

        IllegalArgumentException configurationFailure =
                assertThrows(IllegalArgumentException.class, llmProperties::configuredProviderBackoff);

        assertEquals(
                "app.llm.configured-provider-backoff-seconds must be in range [1, 86400], got: 0",
                configurationFailure.getMessage());
    }

    @Test
    void acceptsMinimumConfiguredProviderBackoff() {
        AppProperties.Llm llmProperties = new AppProperties.Llm();
        llmProperties.setConfiguredProviderBackoffSeconds(MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS);

        assertEquals(
                Duration.ofSeconds(MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS), llmProperties.configuredProviderBackoff());
    }

    @Test
    void acceptsMaximumConfiguredProviderBackoff() {
        AppProperties.Llm llmProperties = new AppProperties.Llm();
        llmProperties.setConfiguredProviderBackoffSeconds(MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS);

        assertEquals(
                Duration.ofSeconds(MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS), llmProperties.configuredProviderBackoff());
    }

    @Test
    void rejectsLongMaximumConfiguredProviderBackoff() {
        AppProperties.Llm llmProperties = new AppProperties.Llm();
        llmProperties.setConfiguredProviderBackoffSeconds(Long.MAX_VALUE);

        IllegalArgumentException configurationFailure =
                assertThrows(IllegalArgumentException.class, llmProperties::configuredProviderBackoff);

        assertEquals(
                "app.llm.configured-provider-backoff-seconds must be in range [1, 86400], got: " + Long.MAX_VALUE,
                configurationFailure.getMessage());
    }

    @Test
    void rejectsNonPositiveRerankerOutputTokenBudget() {
        AppProperties appProperties = validAppProperties();
        appProperties.getLlm().setRerankerOutputTokenBudget(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsBlankOpenAiEmbeddingBaseUrl() {
        AppProperties appProperties = validAppProperties();
        appProperties.getEmbeddings().setOpenAiBaseUrl("");

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsBlankRemoteEmbeddingModel() {
        AppProperties appProperties = validAppProperties();

        assertThrows(
                IllegalArgumentException.class,
                () -> appProperties.getRemoteEmbedding().setModel(" "));
    }

    @Test
    void rejectsNonPositiveRemoteEmbeddingDimensions() {
        AppProperties appProperties = validAppProperties();
        appProperties.getRemoteEmbedding().setDimensions(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    private static AppProperties validAppProperties() {
        AppProperties appProperties = new AppProperties();
        AppProperties.Llm llmProperties = appProperties.getLlm();
        llmProperties.setTemperature(TEST_LLM_TEMPERATURE);
        llmProperties.setRerankerTemperature(TEST_RERANKER_TEMPERATURE);
        llmProperties.setCompletionOutputTokenBudget(TEST_COMPLETION_OUTPUT_TOKEN_BUDGET);
        llmProperties.setEnrichmentOutputTokenBudget(TEST_ENRICHMENT_OUTPUT_TOKEN_BUDGET);
        llmProperties.setRerankerOutputTokenBudget(TEST_RERANKER_OUTPUT_TOKEN_BUDGET);
        llmProperties.setConfiguredProviderBackoffSeconds(TEST_CONFIGURED_PROVIDER_BACKOFF_SECONDS);
        appProperties.getEmbeddings().setOpenAiBaseUrl(TEST_OPENAI_EMBEDDING_BASE_URL);
        appProperties.getEmbeddings().setOpenAiModel("");
        appProperties.getRemoteEmbedding().setModel(TEST_REMOTE_EMBEDDING_MODEL);
        appProperties.getRemoteEmbedding().setDimensions(TEST_REMOTE_EMBEDDING_DIMENSIONS);
        return appProperties;
    }
}
