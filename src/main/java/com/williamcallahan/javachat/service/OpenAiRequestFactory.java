package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseTextConfig;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.domain.prompt.ContextDocumentSegment;
import com.williamcallahan.javachat.domain.prompt.ConversationTurnSegment;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private static final Set<String> SUPPORTED_REASONING_EFFORTS =
            Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final String SUPPORTED_REASONING_EFFORT_DESCRIPTION = "none, minimal, low, medium, high, xhigh, max";

    private static final int GPT54_INPUT_TOKEN_BUDGET = 100_000;

    /** Truncation notice for requests exceeding the application-owned prompt limit. */
    private static final String TRUNCATION_NOTICE_GENERIC = "[Context truncated due to model input limit]\n\n";

    private final Chunker chunker;
    private final PromptTruncator promptTruncator;
    private final String openaiModel;
    private final int completionOutputTokenBudget;
    private final int promptContentTokenBudget;
    private final Optional<ReasoningEffort> reasoningEffort;

    /**
     * Creates a request factory with model-id and truncation settings from application properties.
     *
     * @param chunker token-aware chunking service used for completion prompt truncation
     * @param promptTruncator structured prompt truncator for streaming requests
     * @param openaiModel configured OpenAI model id
     * @param appProperties typed application configuration for LLM generation policy
     * @throws IllegalArgumentException if the OpenAI model or reasoning effort is invalid
     */
    public OpenAiRequestFactory(
            Chunker chunker,
            PromptTruncator promptTruncator,
            @Value("${OPENAI_MODEL:" + ModelConfiguration.DEFAULT_MODEL + "}") String openaiModel,
            AppProperties appProperties) {
        this.chunker = chunker;
        this.promptTruncator = promptTruncator;
        this.openaiModel = requireUniversalChatModel(openaiModel);
        this.promptContentTokenBudget = GPT54_INPUT_TOKEN_BUDGET - chunker.countTokens(TRUNCATION_NOTICE_GENERIC);
        AppProperties.Llm llmConfiguration = appProperties.getLlm();
        this.completionOutputTokenBudget = llmConfiguration.getCompletionOutputTokenBudget();
        this.reasoningEffort = resolveReasoningEffort(llmConfiguration.getReasoningEffort());
    }

    /**
     * Builds a streaming request payload and returns the resolved model identifier.
     *
     * @param structuredPrompt typed prompt segments to stream
     * @param temperature response temperature
     * @return request parameters with resolved model id
     */
    public OpenAiPreparedRequest prepareStreamingRequest(StructuredPrompt structuredPrompt, double temperature) {
        String modelId = configuredModelId();

        PromptTruncator.TruncatedPrompt truncatedPrompt =
                promptTruncator.truncate(structuredPrompt, promptContentTokenBudget);
        if (truncatedPrompt.wasTruncated()) {
            log.info(
                    "[LLM] Prompt truncated for streaming (model={}, contextDocs={}, conversationTurns={})",
                    modelId,
                    truncatedPrompt.contextDocumentCount(),
                    truncatedPrompt.conversationTurnCount());
        }

        List<ResponseInputItem> responseInputItems = buildResponseInputItems(truncatedPrompt);
        ResponseCreateParams responseParams = buildResponseParams(responseInputItems, temperature, modelId).toBuilder()
                .instructions(truncatedPrompt.prompt().system().content())
                .build();
        return new OpenAiPreparedRequest(responseParams, modelId, truncatedPrompt.prompt());
    }

    /**
     * Preserves each structured prompt segment's native Responses API role.
     *
     * <p>Retrieved reference text remains unprivileged user input, prior conversation turns retain
     * their user or assistant identity, and the active question is always the final user message.
     * Only application-owned instructions and truncation guidance receive developer authority.</p>
     */
    private static List<ResponseInputItem> buildResponseInputItems(PromptTruncator.TruncatedPrompt truncatedPrompt) {
        StructuredPrompt prompt = truncatedPrompt.prompt();
        List<ResponseInputItem> responseInputItems = new ArrayList<>();
        if (truncatedPrompt.wasTruncated()) {
            responseInputItems.add(textInputItem(EasyInputMessage.Role.DEVELOPER, TRUNCATION_NOTICE_GENERIC.strip()));
        }
        for (ContextDocumentSegment contextDocument : prompt.contextDocuments()) {
            responseInputItems.add(textInputItem(EasyInputMessage.Role.USER, contextDocument.content()));
        }
        for (ConversationTurnSegment conversationTurn : prompt.conversationHistory()) {
            EasyInputMessage.Role role =
                    conversationTurn.isAssistantTurn() ? EasyInputMessage.Role.ASSISTANT : EasyInputMessage.Role.USER;
            responseInputItems.add(textInputItem(role, conversationTurn.messageText()));
        }
        responseInputItems.add(
                textInputItem(EasyInputMessage.Role.USER, prompt.currentQuery().queryText()));
        return List.copyOf(responseInputItems);
    }

    private static ResponseInputItem textInputItem(EasyInputMessage.Role role, String messageText) {
        EasyInputMessage inputMessage =
                EasyInputMessage.builder().role(role).content(messageText).build();
        return ResponseInputItem.ofEasyInputMessage(inputMessage);
    }

    /**
     * Builds completion request parameters for the selected provider.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @return request payload ready for SDK execution
     */
    public ResponseCreateParams buildCompletionRequest(String prompt, double temperature) {
        return buildCompletionRequest(prompt, temperature, null, false);
    }

    /**
     * Builds completion request parameters with an explicit output budget.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return request payload ready for SDK execution
     */
    public ResponseCreateParams buildCompletionRequest(String prompt, double temperature, int maximumOutputTokens) {
        if (maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
        return buildCompletionRequest(prompt, temperature, Integer.valueOf(maximumOutputTokens), false);
    }

    /**
     * Builds completion request parameters that require a JSON object response.
     *
     * @param prompt completion prompt
     * @param temperature response temperature
     * @param maximumOutputTokens maximum output tokens needed by this caller
     * @return request payload with a declared JSON-object output contract
     */
    public ResponseCreateParams buildJsonCompletionRequest(String prompt, double temperature, int maximumOutputTokens) {
        if (maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
        return buildCompletionRequest(prompt, temperature, Integer.valueOf(maximumOutputTokens), true);
    }

    private ResponseCreateParams buildCompletionRequest(
            String prompt, double temperature, Integer maximumOutputTokens, boolean requireJsonObject) {
        String modelId = configuredModelId();
        String truncatedPrompt = truncatePromptForCompletion(prompt);
        return buildResponseParams(truncatedPrompt, temperature, modelId, maximumOutputTokens, requireJsonObject);
    }

    private String truncatePromptForCompletion(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt;
        }

        String truncatedPrompt = chunker.keepLastTokens(prompt, promptContentTokenBudget);

        if (truncatedPrompt.length() < prompt.length()) {
            return TRUNCATION_NOTICE_GENERIC + truncatedPrompt;
        }

        return prompt;
    }

    private ResponseCreateParams buildResponseParams(
            List<ResponseInputItem> responseInputItems, double temperature, String normalizedModelId) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .inputOfResponse(responseInputItems)
                .model(ResponsesModel.ofString(normalizedModelId));
        return configureResponseParams(builder, temperature, normalizedModelId, null, false);
    }

    private ResponseCreateParams buildResponseParams(
            String prompt,
            double temperature,
            String normalizedModelId,
            Integer maximumOutputTokens,
            boolean requireJsonObject) {
        ResponseCreateParams.Builder builder =
                ResponseCreateParams.builder().input(prompt).model(ResponsesModel.ofString(normalizedModelId));
        return configureResponseParams(builder, temperature, normalizedModelId, maximumOutputTokens, requireJsonObject);
    }

    private ResponseCreateParams configureResponseParams(
            ResponseCreateParams.Builder builder,
            double temperature,
            String normalizedModelId,
            Integer maximumOutputTokens,
            boolean requireJsonObject) {
        boolean gpt5Family = ModelConfiguration.isGpt5Family(normalizedModelId);
        boolean reasoningModel =
                gpt5Family || canonicalModelName(normalizedModelId).startsWith("o");
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

    String configuredModelId() {
        return openaiModel;
    }

    private static String requireUniversalChatModel(String configuredModel) {
        if (!ModelConfiguration.DEFAULT_MODEL.equals(configuredModel)) {
            throw new IllegalArgumentException(
                    "OPENAI_MODEL must be " + ModelConfiguration.DEFAULT_MODEL + " for every Java Chat LLM request");
        }
        return configuredModel;
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

        String normalizedReasoningEffort = AsciiTextNormalizer.toLowerAscii(reasoningEffortSetting.trim());
        ReasoningEffort configuredReasoningEffort = ReasoningEffort.of(normalizedReasoningEffort);
        try {
            configuredReasoningEffort.known();
        } catch (OpenAIInvalidDataException e) {
            throw invalidReasoningEffort(reasoningEffortSetting, e);
        }
        if (!SUPPORTED_REASONING_EFFORTS.contains(normalizedReasoningEffort)) {
            throw invalidReasoningEffort(reasoningEffortSetting, null);
        }
        return Optional.of(configuredReasoningEffort);
    }

    private static IllegalArgumentException invalidReasoningEffort(
            String reasoningEffortSetting, RuntimeException invalidSdkReasoningEffort) {
        String configurationMessage = "Invalid "
                + REASONING_EFFORT_PROPERTY
                + " value '"
                + reasoningEffortSetting
                + "'. Valid values: "
                + SUPPORTED_REASONING_EFFORT_DESCRIPTION;
        return invalidSdkReasoningEffort == null
                ? new IllegalArgumentException(configurationMessage)
                : new IllegalArgumentException(configurationMessage, invalidSdkReasoningEffort);
    }
}
