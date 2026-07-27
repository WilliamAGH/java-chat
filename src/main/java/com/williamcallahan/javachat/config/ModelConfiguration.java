package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;

/**
 * Owns the chat model default and RAG context policy.
 *
 * <p>The request factory and normal chat retrieval use the same model identifier so
 * reranking, final inference, and RAG policy cannot drift to different defaults.</p>
 */
public final class ModelConfiguration {
    /** Default model identifier when none is configured. */
    public static final String DEFAULT_MODEL = "gpt-5.4";

    private static final char MODEL_VERSION_SEPARATOR = '.';
    private static final String GPT5_FAMILY_PREFIX =
            DEFAULT_MODEL.substring(0, DEFAULT_MODEL.lastIndexOf(MODEL_VERSION_SEPARATOR));

    /** Estimated characters per token for conservative token counting. */
    public static final int ESTIMATED_CHARS_PER_TOKEN = 4;

    /** RAG document limit for constrained provider tiers. */
    public static final int RAG_LIMIT_CONSTRAINED = 3;

    /** Max tokens per RAG document for token-constrained models. */
    public static final int RAG_TOKEN_LIMIT_CONSTRAINED = 600;

    private ModelConfiguration() {
        // Utility class
    }

    /**
     * Determines whether a provider-qualified or bare model identifier belongs to the GPT-5 family.
     *
     * @param modelId provider-qualified or bare model identifier
     * @return true when the canonical model name belongs to the GPT-5 family
     */
    public static boolean isGpt5Family(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalizedModelId = AsciiTextNormalizer.toLowerAscii(modelId.trim());
        int providerSeparatorIndex = normalizedModelId.lastIndexOf('/');
        String canonicalModelName = providerSeparatorIndex < 0
                ? normalizedModelId
                : normalizedModelId.substring(providerSeparatorIndex + 1);
        return canonicalModelName.startsWith(GPT5_FAMILY_PREFIX);
    }
}
