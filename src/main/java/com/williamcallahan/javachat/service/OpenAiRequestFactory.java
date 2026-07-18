package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseTextConfig;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Builds OpenAI-compatible request payloads while enforcing model-specific prompt limits.
 *
 * <p>Centralizing request construction keeps model normalization, truncation behavior,
 * and reasoning options consistent between streaming and non-streaming calls.</p>
 */
@Service
@Lazy(false)
public final class OpenAiRequestFactory {
    private static final Logger log = LoggerFactory.getLogger(OpenAiRequestFactory.class);

    private static final String REASONING_EFFORT_PROPERTY = "app.llm.reasoning-effort";
    private static final String KNOWN_OPENAI_REASONING_EFFORTS = Arrays.stream(ReasoningEffort.Known.values())
            .map(knownReasoningEffort -> AsciiTextNormalizer.toLowerAscii(knownReasoningEffort.name()))
            .collect(Collectors.joining(", "));

    /** Prefix matching gpt-5, gpt-5.2, gpt-5.2-pro, etc. */
    private static final String GPT_5_MODEL_PREFIX = "gpt-5";

    private static final String GITHUB_MODEL_PROVIDER_PREFIX = "openai/";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.2";
    private static final String DEFAULT_GITHUB_MODELS_MODEL = "openai/gpt-5";

    /**
     * Safe token budget under GitHub Models' 8K input tier for its GPT-5 catalog entry.
     * The constraint belongs to that provider tier, not the GPT-5 family: the same family
     * served by OpenAI direct or the LLM gateway accepts far larger inputs and uses
     * {@link #MAX_TOKENS_DEFAULT_INPUT}.
     */
    private static final int MAX_TOKENS_GITHUB_MODELS_GPT5_INPUT = 7000;

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
    private final int completionOutputTokenBudget;
    private final Optional<ReasoningEffort> reasoningEffort;

    /**
     * Creates a request factory with model-id and truncation settings from application properties.
     *
     * @param chunker token-aware chunking service used for completion prompt truncation
     * @param promptTruncator structured prompt truncator for streaming requests
     * @param openaiModel configured OpenAI model id
     * @param githubModelsChatModel configured GitHub Models model id
     * @param appProperties typed application configuration for LLM generation policy
     * @throws IllegalArgumentException if the reasoning effort is not recognized by the OpenAI SDK
     */
    public OpenAiRequestFactory(
            Chunker chunker,
            PromptTruncator promptTruncator,
            @Value("${OPENAI_MODEL:" + DEFAULT_OPENAI_MODEL + "}") String openaiModel,
            @Value("${GITHUB_MODELS_CHAT_MODEL:" + DEFAULT_GITHUB_MODELS_MODEL + "}") String githubModelsChatModel,
            AppProperties appProperties) {
        this.chunker = chunker;
        this.promptTruncator = promptTruncator;
        this.openaiModel = openaiModel;
        this.githubModelsChatModel = githubModelsChatModel;
        AppProperties.Llm llmConfiguration = appProperties.getLlm();
        this.completionOutputTokenBudget = llmConfiguration.getCompletionOutputTokenBudget();
        this.reasoningEffort = resolveReasoningEffort(llmConfiguration.getReasoningEffort());
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
        boolean githubModelsGpt5Constrained = useGitHubModels && isGpt5Family(modelId);
        int tokenLimit = githubModelsGpt5Constrained ? MAX_TOKENS_GITHUB_MODELS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;

        PromptTruncator.TruncatedPrompt truncatedPrompt =
                promptTruncator.truncate(structuredPrompt, tokenLimit, githubModelsGpt5Constrained);
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
        return buildCompletionRequest(prompt, temperature, provider, null, false);
    }

    /**
     * Builds completion request parameters with an explicit output budget.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param provider provider chosen for this request attempt
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return request payload ready for SDK execution
     */
    public ResponseCreateParams buildCompletionRequest(
            String prompt, double temperature, RateLimitService.ApiProvider provider, int maximumOutputTokens) {
        if (maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
        return buildCompletionRequest(prompt, temperature, provider, Integer.valueOf(maximumOutputTokens), false);
    }

    /**
     * Builds completion request parameters that require a JSON object response.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param provider provider chosen for this request attempt
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return request payload with a declared JSON-object output contract
     */
    public ResponseCreateParams buildJsonCompletionRequest(
            String prompt, double temperature, RateLimitService.ApiProvider provider, int maximumOutputTokens) {
        if (maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
        return buildCompletionRequest(prompt, temperature, provider, Integer.valueOf(maximumOutputTokens), true);
    }

    private ResponseCreateParams buildCompletionRequest(
            String prompt,
            double temperature,
            RateLimitService.ApiProvider provider,
            Integer maximumOutputTokens,
            boolean requireJsonObject) {
        boolean useGitHubModels = provider == RateLimitService.ApiProvider.GITHUB_MODELS;
        String modelId = normalizedModelId(useGitHubModels);
        String truncatedPrompt = truncatePromptForCompletion(prompt, modelId, useGitHubModels);
        return buildResponseParams(truncatedPrompt, temperature, modelId, maximumOutputTokens, requireJsonObject);
    }

    private String truncatePromptForCompletion(String prompt, String modelId, boolean useGitHubModels) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }

        // The 7K cap accommodates GitHub Models' 8K input tier for its GPT-5 entry.
        // GPT-5 family on OpenAI direct/the gateway and o-series reasoning models
        // accept >=128K input tokens, so both take the default cap (PR #49 review
        // finding; gateway gpt-5.4 over-truncation).
        boolean githubModelsGpt5Constrained = useGitHubModels && isGpt5Family(modelId);
        int tokenLimit = githubModelsGpt5Constrained ? MAX_TOKENS_GITHUB_MODELS_GPT5_INPUT : MAX_TOKENS_DEFAULT_INPUT;
        String truncatedPrompt = chunker.keepLastTokens(prompt, tokenLimit);

        if (truncatedPrompt.length() < prompt.length()) {
            String truncationNotice = githubModelsGpt5Constrained ? TRUNCATION_NOTICE_GPT5 : TRUNCATION_NOTICE_GENERIC;
            return truncationNotice + truncatedPrompt;
        }

        return prompt;
    }

    private ResponseCreateParams buildResponseParams(String prompt, double temperature, String normalizedModelId) {
        return buildResponseParams(prompt, temperature, normalizedModelId, null);
    }

    private ResponseCreateParams buildResponseParams(
            String prompt, double temperature, String normalizedModelId, Integer maximumOutputTokens) {
        return buildResponseParams(prompt, temperature, normalizedModelId, maximumOutputTokens, false);
    }

    private ResponseCreateParams buildResponseParams(
            String prompt,
            double temperature,
            String normalizedModelId,
            Integer maximumOutputTokens,
            boolean requireJsonObject) {
        boolean gpt5Family = isGpt5Family(normalizedModelId);
        boolean reasoningModel =
                gpt5Family || canonicalModelName(normalizedModelId).startsWith("o");

        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().input(prompt).model(ResponsesModel.ofString(normalizedModelId));

        if (requireJsonObject) {
            builder.text(ResponseTextConfig.builder()
                    .format(ResponseFormatJsonObject.builder().build())
                    .build());
        }

        if (maximumOutputTokens != null) {
            builder.maxOutputTokens(maximumOutputTokens.longValue());
        } else if (gpt5Family) {
            builder.maxOutputTokens((long) completionOutputTokenBudget);
        }

        if (gpt5Family) {
            log.debug("Using GPT-5 family configuration for model: {}", normalizedModelId);

            reasoningEffort.ifPresent(effort ->
                    builder.reasoning(Reasoning.builder().effort(effort).build()));
        } else if (!reasoningModel && Double.isFinite(temperature)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private String normalizedModelId(boolean useGitHubModels) {
        String configuredModel = useGitHubModels ? githubModelsChatModel : openaiModel;
        String normalizedConfiguredModel =
                configuredModel == null ? "" : AsciiTextNormalizer.toLowerAscii(configuredModel.trim());
        if (normalizedConfiguredModel.isEmpty()) {
            return useGitHubModels ? DEFAULT_GITHUB_MODELS_MODEL : DEFAULT_OPENAI_MODEL;
        }
        if (!useGitHubModels || normalizedConfiguredModel.contains("/")) {
            return normalizedConfiguredModel;
        }
        return GITHUB_MODEL_PROVIDER_PREFIX + normalizedConfiguredModel;
    }

    private boolean isGpt5Family(String modelId) {
        return canonicalModelName(modelId).startsWith(GPT_5_MODEL_PREFIX);
    }

    private String canonicalModelName(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        String normalizedModelId = AsciiTextNormalizer.toLowerAscii(modelId.trim());
        int providerSeparatorIndex = normalizedModelId.lastIndexOf('/');
        if (providerSeparatorIndex < 0 || providerSeparatorIndex + 1 >= normalizedModelId.length()) {
            return normalizedModelId;
        }
        return normalizedModelId.substring(providerSeparatorIndex + 1);
    }

    private static Optional<ReasoningEffort> resolveReasoningEffort(String reasoningEffortSetting) {
        if (reasoningEffortSetting == null || reasoningEffortSetting.isBlank()) {
            return Optional.empty();
        }

        ReasoningEffort configuredReasoningEffort =
                ReasoningEffort.of(AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim()));
        try {
            configuredReasoningEffort.known();
        } catch (OpenAIInvalidDataException e) {
            throw new IllegalArgumentException(
                    "Invalid "
                            + REASONING_EFFORT_PROPERTY
                            + " value '"
                            + reasoningEffortSetting
                            + "'. Valid values: "
                            + KNOWN_OPENAI_REASONING_EFFORTS,
                    e);
        }
        return Optional.of(configuredReasoningEffort);
    }
}
