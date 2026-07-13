package com.williamcallahan.javachat.application.completion;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/** Defines the output and timeout contract for one completion request. */
public record CompletionRequestConfiguration(
        OptionalInt maximumOutputTokens, boolean requireJsonObject, Duration requestTimeout) {

    private static final Duration DEFAULT_COMPLETION_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Validates that every configuration represents an executable completion request. */
    public CompletionRequestConfiguration {
        maximumOutputTokens = Objects.requireNonNull(maximumOutputTokens, "maximumOutputTokens");
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maximumOutputTokens.isPresent() && maximumOutputTokens.getAsInt() <= 0) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
        if (requireJsonObject && maximumOutputTokens.isEmpty()) {
            throw new IllegalArgumentException("JSON object completions require a maximum output token budget");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    /** Creates an unbounded text completion with the application timeout. */
    public static CompletionRequestConfiguration defaultText() {
        return new CompletionRequestConfiguration(OptionalInt.empty(), false, DEFAULT_COMPLETION_REQUEST_TIMEOUT);
    }

    /** Creates a bounded text completion with the application timeout. */
    public static CompletionRequestConfiguration boundedText(int maximumOutputTokens) {
        return new CompletionRequestConfiguration(
                OptionalInt.of(maximumOutputTokens), false, DEFAULT_COMPLETION_REQUEST_TIMEOUT);
    }

    /** Returns the application-owned timeout for ordinary completion requests. */
    public static Duration defaultRequestTimeout() {
        return DEFAULT_COMPLETION_REQUEST_TIMEOUT;
    }

    /** Creates a bounded JSON object completion with a caller-owned timeout. */
    public static CompletionRequestConfiguration jsonObject(int maximumOutputTokens, Duration requestTimeout) {
        return new CompletionRequestConfiguration(OptionalInt.of(maximumOutputTokens), true, requestTimeout);
    }
}
