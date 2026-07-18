package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

/**
 * Verifies startup configuration and provider-aware OpenAI-compatible request construction.
 */
class OpenAiRequestFactoryTest {
    private static final int TEST_COMPLETION_OUTPUT_TOKEN_BUDGET = 768;
    private static final Logger APPLICATION_CONTEXT_LOGGER =
            (Logger) LoggerFactory.getLogger(AnnotationConfigApplicationContext.class);
    private static final Logger SPRING_APPLICATION_LOGGER = (Logger) LoggerFactory.getLogger(SpringApplication.class);

    @Test
    void reasoningEffortValidationRunsDuringStartupDespiteGlobalLazyInitialization() {
        Lazy lazyConfiguration = OpenAiRequestFactory.class.getAnnotation(Lazy.class);

        assertNotNull(lazyConfiguration);
        assertFalse(lazyConfiguration.value());
    }

    @Test
    void invalidReasoningEffortFailsLazyApplicationStartup() {
        SpringApplication application = new SpringApplication(OpenAiRequestFactoryStartupConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLazyInitialization(true);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);

        RuntimeException startupFailure;
        try (ExpectedLogEvents applicationContextLogEvents = ExpectedLogEvents.capture(APPLICATION_CONTEXT_LOGGER);
                ExpectedLogEvents springApplicationLogEvents = ExpectedLogEvents.capture(SPRING_APPLICATION_LOGGER)) {
            startupFailure =
                    assertThrows(RuntimeException.class, () -> application.run("--app.llm.reasoning-effort=hgh"));

            assertEquals(1, applicationContextLogEvents.events().size());
            var contextCancellationWarning =
                    applicationContextLogEvents.events().getFirst();
            assertEquals(Level.WARN, contextCancellationWarning.getLevel());
            assertTrue(contextCancellationWarning
                    .getFormattedMessage()
                    .startsWith("Exception encountered during context initialization - cancelling refresh attempt:"));
            assertNull(contextCancellationWarning.getThrowableProxy());

            assertEquals(1, springApplicationLogEvents.events().size());
            var startupError = springApplicationLogEvents.events().getFirst();
            assertEquals(Level.ERROR, startupError.getLevel());
            assertEquals("Application run failed", startupError.getFormattedMessage());
            assertNotNull(startupError.getThrowableProxy());
            assertEquals(
                    "org.springframework.beans.factory.BeanCreationException",
                    startupError.getThrowableProxy().getClassName());
        }

        IllegalArgumentException configurationFailure = findConfigurationFailure(startupFailure);
        assertTrue(configurationFailure.getMessage().contains("Invalid app.llm.reasoning-effort value 'hgh'"));
    }

    @Test
    void reasoningEffortConfigurationIsCaseInsensitive() {
        OpenAiRequestFactory requestFactory = createRequestFactory("HIGH");

        ResponseCreateParams reasoningRequestParams =
                requestFactory.buildCompletionRequest("Explain Java streams", 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(
                ReasoningEffort.HIGH,
                reasoningRequestParams.reasoning().orElseThrow().effort().orElseThrow());
    }

    @Test
    void reasoningEffortConfigurationTrimsSurroundingWhitespace() {
        OpenAiRequestFactory requestFactory = createRequestFactory("\t high \n");

        ResponseCreateParams reasoningRequestParams =
                requestFactory.buildCompletionRequest("Explain Java streams", 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(
                ReasoningEffort.HIGH,
                reasoningRequestParams.reasoning().orElseThrow().effort().orElseThrow());
    }

    @Test
    void invalidReasoningEffortConfigurationFailsBeforeARequestIsBuilt() {
        IllegalArgumentException configurationFailure =
                assertThrows(IllegalArgumentException.class, () -> createRequestFactory("hgh"));

        assertTrue(configurationFailure.getMessage().contains("Invalid app.llm.reasoning-effort value 'hgh'"));
        assertTrue(configurationFailure.getMessage().contains("Valid values:"));
    }

    @Test
    void blankReasoningEffortConfigurationOmitsReasoning() {
        OpenAiRequestFactory requestFactory = createRequestFactory(" \t\n ");

        ResponseCreateParams reasoningRequestParams =
                requestFactory.buildCompletionRequest("Explain Java streams", 0.4, RateLimitService.ApiProvider.OPENAI);

        assertTrue(reasoningRequestParams.reasoning().isEmpty());
    }

    @Test
    void buildCompletionRequestPrefixesGitHubModelWhenProviderPrefixIsMissing() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", appProperties(""));

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain Java streams", 0.4, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals("openai/gpt-5", responseCreateParams.model().orElseThrow().asString());
        assertEquals(
                (long) TEST_COMPLETION_OUTPUT_TOKEN_BUDGET,
                responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildCompletionRequestUsesDefaultGitHubModelWhenUnset() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", " ", appProperties(""));

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain Java records", 0.3, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals("openai/gpt-5", responseCreateParams.model().orElseThrow().asString());
    }

    @Test
    void buildCompletionRequestRetainsQualifiedGitHubModelIdentifier() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-5.2", "xai/grok-3-mini", appProperties(""));

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain sealed classes", 0.25, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals(
                "xai/grok-3-mini", responseCreateParams.model().orElseThrow().asString());
        assertTrue(responseCreateParams.maxOutputTokens().isEmpty());
        assertEquals(0.25, responseCreateParams.temperature().orElseThrow(), 0.000_001);
    }

    @Test
    void buildCompletionRequestAppliesCallerOutputBudget() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "qwen3.6:onprem", "openai/gpt-5", appProperties(""));

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Rank these documents", 0.0, RateLimitService.ApiProvider.OPENAI, 128);

        assertEquals(128L, responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildJsonCompletionRequestDeclaresJsonObjectOutput() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gemma-4-26b-a4b", "openai/gpt-5", appProperties(""));

        ResponseCreateParams responseCreateParams = requestFactory.buildJsonCompletionRequest(
                "Rank these documents", 0.0, RateLimitService.ApiProvider.OPENAI, 128);

        assertTrue(
                responseCreateParams.text().orElseThrow().format().orElseThrow().isJsonObject());
        assertEquals(128L, responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildCompletionRequestKeepsPromptWithinSelectedOpenAiModelLimit() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-4o", "openai/gpt-5", appProperties(""));
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestDoesNotApplyGitHubModelsCapToOpenAiGpt5Family() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-5.4", "openai/gpt-5", appProperties(""));
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestDoesNotApplyGpt5LimitToOSeriesModels() {
        OpenAiRequestFactory requestFactory = new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "o3-mini", "openai/gpt-5", appProperties(""));
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestTruncatesPromptForSelectedGitHubModelsLimit() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-4o", "gpt-5", appProperties(""));
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.GITHUB_MODELS);

        String truncatedPrompt = responseCreateParams.input().orElseThrow().asText();
        assertTrue(truncatedPrompt.startsWith("[Context truncated due to GPT-5 8K input limit]"));
        assertTrue(truncatedPrompt.length() < prompt.length());
    }

    private OpenAiRequestFactory createRequestFactory(String reasoningEffortSetting) {
        return new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-5.2", "openai/gpt-5", appProperties(reasoningEffortSetting));
    }

    private AppProperties appProperties(String reasoningEffortSetting) {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setReasoningEffort(reasoningEffortSetting);
        appProperties.getLlm().setCompletionOutputTokenBudget(TEST_COMPLETION_OUTPUT_TOKEN_BUDGET);
        return appProperties;
    }

    private IllegalArgumentException findConfigurationFailure(RuntimeException startupFailure) {
        Throwable failureCause = startupFailure;
        while (failureCause != null) {
            if (failureCause instanceof IllegalArgumentException configurationFailure) {
                return configurationFailure;
            }
            failureCause = failureCause.getCause();
        }
        throw new AssertionError("Expected an IllegalArgumentException in the startup failure chain", startupFailure);
    }

    /** Supplies the minimum graph needed to verify eager startup validation. */
    @Configuration(proxyBeanMethods = false)
    @Import(OpenAiRequestFactory.class)
    @EnableConfigurationProperties(AppProperties.class)
    static class OpenAiRequestFactoryStartupConfiguration {

        @Bean
        Chunker chunker() {
            return new Chunker();
        }

        @Bean
        PromptTruncator promptTruncator() {
            return new PromptTruncator();
        }
    }
}
