package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

/**
 * Verifies startup configuration and provider-aware OpenAI-compatible request construction.
 */
class OpenAiRequestFactoryTest {

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

        RuntimeException startupFailure =
                assertThrows(RuntimeException.class, () -> application.run("--OPENAI_REASONING_EFFORT=hgh"));

        IllegalArgumentException configurationFailure = findConfigurationFailure(startupFailure);
        assertTrue(configurationFailure.getMessage().contains("Invalid OPENAI_REASONING_EFFORT value 'hgh'"));
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

        assertTrue(configurationFailure.getMessage().contains("Invalid OPENAI_REASONING_EFFORT value 'hgh'"));
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
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain Java streams", 0.4, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals("openai/gpt-5", responseCreateParams.model().orElseThrow().asString());
        assertEquals(4000L, responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildCompletionRequestUsesDefaultGitHubModelWhenUnset() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", " ", "");

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain Java records", 0.3, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals("openai/gpt-5", responseCreateParams.model().orElseThrow().asString());
    }

    @Test
    void buildCompletionRequestRetainsQualifiedGitHubModelIdentifier() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "xai/grok-3-mini", "");

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Explain sealed classes", 0.25, RateLimitService.ApiProvider.GITHUB_MODELS);

        assertEquals(
                "xai/grok-3-mini", responseCreateParams.model().orElseThrow().asString());
        assertTrue(responseCreateParams.maxOutputTokens().isEmpty());
        assertEquals(0.25, responseCreateParams.temperature().orElseThrow(), 0.000_001);
    }

    @Test
    void buildCompletionRequestAppliesCallerOutputBudget() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "qwen3.6:onprem", "openai/gpt-5", "");

        ResponseCreateParams responseCreateParams = requestFactory.buildCompletionRequest(
                "Rank these documents", 0.0, RateLimitService.ApiProvider.OPENAI, 128);

        assertEquals(128L, responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildJsonCompletionRequestDeclaresJsonObjectOutput() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gemma-4-26b-a4b", "openai/gpt-5", "");

        ResponseCreateParams responseCreateParams = requestFactory.buildJsonCompletionRequest(
                "Rank these documents", 0.0, RateLimitService.ApiProvider.OPENAI, 128);

        assertTrue(
                responseCreateParams.text().orElseThrow().format().orElseThrow().isJsonObject());
        assertEquals(128L, responseCreateParams.maxOutputTokens().orElseThrow());
    }

    @Test
    void buildCompletionRequestKeepsPromptWithinSelectedOpenAiModelLimit() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-4o", "openai/gpt-5", "");
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestDoesNotApplyGitHubModelsCapToOpenAiGpt5Family() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.4", "openai/gpt-5", "");
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestDoesNotApplyGpt5LimitToOSeriesModels() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "o3-mini", "openai/gpt-5", "");
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.OPENAI);

        assertEquals(prompt, responseCreateParams.input().orElseThrow().asText());
    }

    @Test
    void buildCompletionRequestTruncatesPromptForSelectedGitHubModelsLimit() {
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-4o", "gpt-5", "");
        String prompt = "context ".repeat(8_000);

        ResponseCreateParams responseCreateParams =
                requestFactory.buildCompletionRequest(prompt, 0.4, RateLimitService.ApiProvider.GITHUB_MODELS);

        String truncatedPrompt = responseCreateParams.input().orElseThrow().asText();
        assertTrue(truncatedPrompt.startsWith("[Context truncated due to GPT-5 8K input limit]"));
        assertTrue(truncatedPrompt.length() < prompt.length());
    }

    private OpenAiRequestFactory createRequestFactory(String reasoningEffortSetting) {
        return new OpenAiRequestFactory(
                new Chunker(), new PromptTruncator(), "gpt-5.2", "openai/gpt-5", reasoningEffortSetting);
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
