package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;

/**
 * Centralized model configuration constants and detection utilities.
 *
 * <p>Provides a single source of truth for model identifiers and family detection,
 * eliminating hardcoded model strings scattered throughout services.</p>
 */
public final class ModelConfiguration {

    /** Default model identifier when none is configured. */
    public static final String DEFAULT_MODEL = "gpt-5.2";

    /** Model family prefix for GPT-5.x models with token constraints. */
    private static final String GPT5_FAMILY_PREFIX = "gpt-5";

    /** Estimated characters per token for conservative token counting. */
    public static final int ESTIMATED_CHARS_PER_TOKEN = 4;

    /** RAG document limit for token-constrained models like GPT-5.2. */
    public static final int RAG_LIMIT_CONSTRAINED = 3;

    /** Max tokens per RAG document for token-constrained models. */
    public static final int RAG_TOKEN_LIMIT_CONSTRAINED = 600;

    private ModelConfiguration() {
        // Utility class
    }

    /**
     * Determines if the given model is token-constrained (requires reduced RAG context).
     *
     * <p>Currently the GPT-5.x family has an 8K input token limit, requiring reduced RAG context.</p>
     *
     * @param modelHint optional model hint from request
     * @return true if reduced RAG context should be used
     */
    public static boolean isTokenConstrained(String modelHint) {
        if (modelHint == null || modelHint.isBlank()) {
            return false;
        }
        String normalized = AsciiTextNormalizer.toLowerAscii(modelHint.trim());
        return normalized.startsWith(GPT5_FAMILY_PREFIX);
    }
}
