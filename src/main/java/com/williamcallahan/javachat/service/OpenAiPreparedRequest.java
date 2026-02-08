package com.williamcallahan.javachat.service;

import com.openai.models.responses.ResponseCreateParams;
import java.util.Objects;

/**
 * Captures the request payload and normalized model id for a single OpenAI-compatible call.
 *
 * @param responseParams request parameters ready for SDK execution
 * @param modelId normalized model identifier used for this request
 */
record OpenAiPreparedRequest(ResponseCreateParams responseParams, String modelId) {
    OpenAiPreparedRequest {
        Objects.requireNonNull(responseParams, "responseParams");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be null or blank");
        }
    }
}
