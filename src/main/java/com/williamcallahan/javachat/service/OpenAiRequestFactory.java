package com.williamcallahan.javachat.service;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.ResponseCreateParams;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds OpenAI-compatible request payloads while enforcing model-specific prompt limits.
 *
 * <p>Centralizing request construction keeps model normalization, truncation behavior,
 * and reasoning options consistent between streaming and non-streaming calls.</p>
 */
@Service
public class OpenAiRequestFactory {
    private static final Logger log = LoggerFactory.getLogger(OpenAiRequestFactory.class);

    private static final int MAX_COMPLETION_TOKENS = 4000;

    /** Prefix matching gpt-5, gpt-5.2, gpt-5.2-pro, etc. */
    private static final String GPT_5_MODEL_PREFIX = "gpt-5";

    /** Safe token budget for GPT-5.2 input (8K limit). */
    private static final int MAX_TOKENS_GPT5_INPUT = 7000;

    /** Generous token budget for high-context models. */
    private static final int MAX_TOKENS_DEFAULT_INPUT = 100_000;

    /** Truncation notice for GPT-5 family models with 8K input limit. */
    private static final String TRUNCATION_NOTICE_GPT5 = "[Context truncated due to GPT-5 8K input limit]\n\n";

    /** Truncation notice for other models with larger limits. */
    private static final String TRUNCATION_NOTICE_GENERIC = "[Context truncated due to model input limit]\n\n";

    private final Chunker chunker;
    private final PromptTruncator promptTruncator;
    private final String openaiModel;
    private final String githubModelsChatModel;
    private final String reasoningEffortSetting;

    /**
     * Creates a request factory with model-id and truncation settings from application properties.
     *
     * @param chunker token-aware chunking service used for completion prompt truncation
     * @param promptTruncator structured prompt truncator for streaming requests
     * @param openaiModel configured OpenAI model id
     * @param githubModelsChatModel configured GitHub Models model id
     * @param reasoningEffortSetting optional reasoning effort for GPT-5 family models
     */
    public OpenAiRequestFactory(
            Chunker chunker,
            PromptTruncator promptTruncator,
            @Value("${OPENAI_MODEL:gpt-5.2}") String openaiModel,
            @Value("${GITHUB_MODELS_CHAT_MODEL:gpt-5}") String githubModelsChatModel,
            @Value("${OPENAI_REASONING_EFFORT:}") String reasoningEffortSetting) {
        this.chunker = chunker;
        this.promptTruncator = promptTruncator;
        this.openaiModel = openaiModel;
        this.githubModelsChatModel = githubModelsChatModel;
        this.reasoningEffortSetting = reasoningEffortSetting;
    }

    /**
     * Builds a streaming request payload and returns the resolved model identifier.
     *
     * @param structuredPrompt typed prompt segments to stream
     * @param temperature response temperature
     * @param provider provider chosen for this request attempt
     * @return request parameters with resolved model id
     */
    public OpenAiPreparedRequest prepareStreamingRequest(
            StructuredPrompt structuredPrompt, double temperature, RateLimitService.ApiProvider provider) {
        boolean useGitHubModels = provider == RateLimitService.ApiProvider.GITHUB_MODELS;
        String modelId = normalizedModelId(useGitHubModels);
        boolean gpt5Family = isGpt5Family(modelId);
        int tokenLimit = gpt5Family ? MAX_TOKENS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;

        PromptTruncator.TruncatedPrompt truncatedPrompt =
                promptTruncator.truncate(structuredPrompt, tokenLimit, gpt5Family);
        if (truncatedPrompt.wasTruncated()) {
            log.info(
                    "[LLM] Prompt truncated for streaming (providerId={}, model={}, contextDocs={}, conversationTurns={})",
                    provider.ordinal(),
                    modelId,
                    truncatedPrompt.contextDocumentCount(),
                    truncatedPrompt.conversationTurnCount());
        }

        ResponseCreateParams responseParams = buildResponseParams(truncatedPrompt.render(), temperature, modelId);
        return new OpenAiPreparedRequest(responseParams, modelId);
    }

    /**
     * Builds completion request parameters for the selected provider.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param provider provider chosen for this request attempt
     * @return request payload ready for SDK execution
     */
    public ResponseCreateParams buildCompletionRequest(
            String prompt, double temperature, RateLimitService.ApiProvider provider) {
        boolean useGitHubModels = provider == RateLimitService.ApiProvider.GITHUB_MODELS;
        String modelId = normalizedModelId(useGitHubModels);
        return buildResponseParams(prompt, temperature, modelId);
    }

    /**
     * Truncates completion prompts to conservative model-safe token limits.
     *
     * @param prompt full completion prompt
     * @return original prompt when no truncation is required, otherwise a notice-prefixed prompt
     */
    public String truncatePromptForCompletion(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }

        String openaiModelId = normalizedModelId(false);
        String githubModelId = normalizedModelId(true);
        boolean gpt5Family = isGpt5Family(openaiModelId) || isGpt5Family(githubModelId);
        boolean reasoningModel = gpt5Family || openaiModelId.startsWith("o") || githubModelId.startsWith("o");

        int tokenLimit = reasoningModel ? MAX_TOKENS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;
        String truncatedPrompt = chunker.keepLastTokens(prompt, tokenLimit);

        if (truncatedPrompt.length() < prompt.length()) {
            String truncationNotice = gpt5Family ? TRUNCATION_NOTICE_GPT5 : TRUNCATION_NOTICE_GENERIC;
            return truncationNotice + truncatedPrompt;
        }

        return prompt;
    }

    private ResponseCreateParams buildResponseParams(String prompt, double temperature, String normalizedModelId) {
        boolean gpt5Family = isGpt5Family(normalizedModelId);
        boolean reasoningModel = gpt5Family || normalizedModelId.startsWith("o");

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().input(prompt).model(ResponsesModel.ofString(normalizedModelId));

        if (gpt5Family) {
            builder.maxOutputTokens((long) MAX_COMPLETION_TOKENS);
            log.debug("Using GPT-5 family configuration for model: {}", normalizedModelId);

            resolveReasoningEffort()
                    .ifPresent(effort ->
                            builder.reasoning(Reasoning.builder().effort(effort).build()));
        } else if (!reasoningModel && Double.isFinite(temperature)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private String normalizedModelId(boolean useGitHubModels) {
        String rawModelId;
        String defaultModel;
        if (useGitHubModels) {
            rawModelId = githubModelsChatModel == null ? "" : githubModelsChatModel.trim();
            defaultModel = "gpt-5";
        } else {
            rawModelId = openaiModel == null ? "" : openaiModel.trim();
            defaultModel = "gpt-5.2";
        }
        return rawModelId.isEmpty() ? defaultModel : AsciiTextNormalizer.toLowerAscii(rawModelId);
    }

    private boolean isGpt5Family(String modelId) {
        return modelId != null && modelId.startsWith(GPT_5_MODEL_PREFIX);
    }

    private Optional<ReasoningEffort> resolveReasoningEffort() {
        if (reasoningEffortSetting == null || reasoningEffortSetting.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ReasoningEffort.of(AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim())));
    }
}
