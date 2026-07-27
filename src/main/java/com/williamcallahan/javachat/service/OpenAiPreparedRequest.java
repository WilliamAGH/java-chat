package com.williamcallahan.javachat.service;

import com.openai.models.responses.ResponseCreateParams;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import java.util.Objects;

/**
 * Captures the request payload and normalized model id for a single OpenAI-compatible call.
 *
 * @param responseParams request parameters ready for SDK execution
 * @param modelId normalized model identifier used for this request
 * @param structuredPrompt exact post-truncation prompt represented by the request parameters
 */
record OpenAiPreparedRequest(ResponseCreateParams responseParams, String modelId, StructuredPrompt structuredPrompt) {
    OpenAiPreparedRequest {
        Objects.requireNonNull(responseParams, "responseParams");
        Objects.requireNonNull(structuredPrompt, "structuredPrompt");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be null or blank");
        }
    }
}
