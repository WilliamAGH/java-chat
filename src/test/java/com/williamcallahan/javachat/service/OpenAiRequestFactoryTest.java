package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.models.responses.ResponseCreateParams;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import org.junit.jupiter.api.Test;

/**
 * Verifies provider-aware model normalization for OpenAI-compatible request payloads.
 */
class OpenAiRequestFactoryTest {

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
}
