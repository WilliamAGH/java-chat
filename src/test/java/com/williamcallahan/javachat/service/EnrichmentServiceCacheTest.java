package com.williamcallahan.javachat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.CacheConfig;
import com.williamcallahan.javachat.model.Enrichment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** Verifies enrichment cache identity includes the retrieved context snippets. */
class EnrichmentServiceCacheTest {
    private static final String ENRICHMENT_USER_QUERY = "How does List work?";
    private static final String ENRICHMENT_JDK_VERSION = "25";
    private static final String ENRICHMENT_INITIAL_CONTEXT_SNIPPET = "The initial List documentation.";
    private static final String ENRICHMENT_FRESH_CONTEXT_SNIPPET = "The refreshed List documentation.";
    private static final String ENRICHMENT_INITIAL_HINT = "Initial hint";
    private static final String ENRICHMENT_FRESH_HINT = "Fresh hint";
    private static final String ENRICHMENT_INITIAL_JSON = "{\"hints\":[\"" + ENRICHMENT_INITIAL_HINT + "\"]}";
    private static final String ENRICHMENT_FRESH_JSON = "{\"hints\":[\"" + ENRICHMENT_FRESH_HINT + "\"]}";
    private static final double ENRICHMENT_TEMPERATURE = 0.4;
    private static final double TEST_RERANKER_TEMPERATURE = 0.2;
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final int TEST_ENRICHMENT_OUTPUT_TOKEN_BUDGET = 640;
    private static final int TEST_RERANKER_OUTPUT_TOKEN_BUDGET = 256;
    private static final long TEST_CONFIGURED_PROVIDER_BACKOFF_SECONDS = 120L;
    private static final int ENRICHMENT_CACHE_MISS_COMPLETION_COUNT = 2;

    @Test
    void shouldRecomputeEnrichmentWhenContextSnippetsChange() {
        try (AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext(
                CacheConfig.class, JacksonAutoConfiguration.class, EnrichmentCacheTestConfiguration.class)) {
            EnrichmentService enrichmentService = applicationContext.getBean(EnrichmentService.class);
            OpenAIStreamingService streamingService = applicationContext.getBean(OpenAIStreamingService.class);
            given(streamingService.isAvailable()).willReturn(true);
            given(streamingService.completeJsonObject(anyString(), eq(ENRICHMENT_TEMPERATURE), anyInt()))
                    .willReturn(Mono.just(ENRICHMENT_INITIAL_JSON))
                    .willReturn(Mono.just(ENRICHMENT_FRESH_JSON));

            Enrichment initialContextEnrichment = enrichmentService.enrich(
                    ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, List.of(ENRICHMENT_INITIAL_CONTEXT_SNIPPET));
            Enrichment freshContextEnrichment = enrichmentService.enrich(
                    ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, List.of(ENRICHMENT_FRESH_CONTEXT_SNIPPET));
            Enrichment repeatedInitialContextEnrichment = enrichmentService.enrich(
                    ENRICHMENT_USER_QUERY, ENRICHMENT_JDK_VERSION, List.of(ENRICHMENT_INITIAL_CONTEXT_SNIPPET));

            assertThat(initialContextEnrichment.getHints()).containsExactly(ENRICHMENT_INITIAL_HINT);
            assertThat(freshContextEnrichment.getHints()).containsExactly(ENRICHMENT_FRESH_HINT);
            assertThat(repeatedInitialContextEnrichment.getHints()).containsExactly(ENRICHMENT_INITIAL_HINT);
            verify(streamingService, times(ENRICHMENT_CACHE_MISS_COMPLETION_COUNT))
                    .completeJsonObject(anyString(), eq(ENRICHMENT_TEMPERATURE), anyInt());
        }
    }

    /** Supplies the focused cache test graph without starting the full application. */
    @Configuration(proxyBeanMethods = false)
    static class EnrichmentCacheTestConfiguration {

        @Bean
        OpenAIStreamingService openAIStreamingService() {
            return mock(OpenAIStreamingService.class);
        }

        @Bean
        EnrichmentService enrichmentService(
                ObjectMapper objectMapper, OpenAIStreamingService streamingService, AppProperties appProperties) {
            return new EnrichmentService(objectMapper, streamingService, appProperties);
        }

        @Bean
        AppProperties appProperties() {
            AppProperties appProperties = new AppProperties();
            AppProperties.Llm llmProperties = appProperties.getLlm();
            llmProperties.setTemperature(ENRICHMENT_TEMPERATURE);
            llmProperties.setRerankerTemperature(TEST_RERANKER_TEMPERATURE);
            llmProperties.setCompletionOutputTokenBudget(TEST_COMPLETION_OUTPUT_TOKEN_BUDGET);
            llmProperties.setEnrichmentOutputTokenBudget(TEST_ENRICHMENT_OUTPUT_TOKEN_BUDGET);
            llmProperties.setRerankerOutputTokenBudget(TEST_RERANKER_OUTPUT_TOKEN_BUDGET);
            llmProperties.setConfiguredProviderBackoffSeconds(TEST_CONFIGURED_PROVIDER_BACKOFF_SECONDS);
            return appProperties;
        }
    }
}
