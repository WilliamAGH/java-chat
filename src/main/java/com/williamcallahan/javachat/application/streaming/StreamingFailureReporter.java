package com.williamcallahan.javachat.application.streaming;

/**
 * Reports a terminal provider failure without exposing provider-specific exception types to orchestration code.
 */
public interface StreamingFailureReporter {

    /**
     * Reports one terminal failure and returns the exception that preserves its upstream cause.
     *
     * @param upstreamFailure failure returned by the provider transport
     * @param terminalAttempt immutable context of the terminal provider attempt
     * @return reported exception to propagate through the stream
     */
    RuntimeException reportTerminalFailure(Throwable upstreamFailure, TerminalAttempt terminalAttempt);

    /** Captures the provider attempt that became terminal. */
    record TerminalAttempt(
            String providerName, String modelId, int currentAttempt, int maxAttempts, boolean emittedTextChunk) {

        /**
         * Validates immutable context before a provider reporter can emit it.
         *
         * @param providerName provider selected for the terminal attempt
         * @param modelId model selected for the terminal attempt
         * @param currentAttempt one-based terminal attempt number
         * @param maxAttempts maximum number of allowed attempts
         * @param emittedTextChunk whether visible text was emitted before the failure
         */
        public TerminalAttempt {
            if (providerName == null || providerName.isBlank()) {
                throw new IllegalArgumentException("providerName must not be blank");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId must not be blank");
            }
            if (currentAttempt < 1) {
                throw new IllegalArgumentException("currentAttempt must be positive");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            if (currentAttempt > maxAttempts) {
                throw new IllegalArgumentException("currentAttempt must not exceed maxAttempts");
            }
        }
    }
}
