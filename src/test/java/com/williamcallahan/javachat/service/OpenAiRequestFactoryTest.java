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
}
