package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIException;
import java.io.Serial;
import java.util.Optional;

/**
 * Preserves finite Responses API terminal states for retry and backoff decisions.
 *
 * <p>Provider messages are deliberately excluded because terminal events may contain
 * request-specific or sensitive text. Only protocol-defined codes and reasons that affect
 * application behavior are retained.</p>
 */
final class OpenAiResponseStreamException extends OpenAIException {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final String SERVER_ERROR_CODE = "server_error";
    private static final String RATE_LIMIT_EXCEEDED_CODE = "rate_limit_exceeded";
    private static final String MAX_OUTPUT_TOKENS_REASON = "max_output_tokens";
    private static final String CONTENT_FILTER_REASON = "content_filter";

    /** Identifies only the protocol terminal reasons that change application behavior. */
    enum TerminalReason {
        SERVER_ERROR,
        RATE_LIMIT_EXCEEDED,
        ERROR,
        FAILED,
        MAX_OUTPUT_TOKENS,
        CONTENT_FILTER,
        INCOMPLETE,
        MISSING_COMPLETION,
        NO_VISIBLE_TEXT
    }

    private final TerminalReason terminalReason;

    private OpenAiResponseStreamException(String message, TerminalReason terminalReason) {
        super(message);
        this.terminalReason = terminalReason;
    }

    static OpenAiResponseStreamException error(Optional<String> providerCode) {
        TerminalReason terminalReason = terminalReasonForErrorCode(providerCode).orElse(TerminalReason.ERROR);
        return new OpenAiResponseStreamException(messageForError(terminalReason), terminalReason);
    }

    static OpenAiResponseStreamException failed(Optional<String> providerCode) {
        TerminalReason terminalReason = terminalReasonForErrorCode(providerCode).orElse(TerminalReason.FAILED);
        return new OpenAiResponseStreamException(messageForFailure(terminalReason), terminalReason);
    }

    static OpenAiResponseStreamException incomplete(Optional<String> incompleteReason) {
        TerminalReason terminalReason = incompleteReason
                .flatMap(OpenAiResponseStreamException::terminalReasonForIncompleteReason)
                .orElse(TerminalReason.INCOMPLETE);
        return new OpenAiResponseStreamException(messageForIncompleteResponse(terminalReason), terminalReason);
    }

    static OpenAiResponseStreamException missingCompletion() {
        return new OpenAiResponseStreamException(
                "Provider stream ended before response.completed", TerminalReason.MISSING_COMPLETION);
    }

    static OpenAiResponseStreamException withoutVisibleText() {
        return new OpenAiResponseStreamException(
                "Provider stream completed without visible text", TerminalReason.NO_VISIBLE_TEXT);
    }

    TerminalReason terminalReason() {
        return terminalReason;
    }

    boolean isRetryable() {
        return terminalReason == TerminalReason.SERVER_ERROR || terminalReason == TerminalReason.MISSING_COMPLETION;
    }

    boolean startsConfiguredProviderBackoff() {
        return terminalReason == TerminalReason.SERVER_ERROR
                || terminalReason == TerminalReason.RATE_LIMIT_EXCEEDED
                || terminalReason == TerminalReason.MISSING_COMPLETION;
    }

    private static Optional<TerminalReason> terminalReasonForErrorCode(Optional<String> providerCode) {
        return providerCode.flatMap(code -> switch (code) {
            case SERVER_ERROR_CODE -> Optional.of(TerminalReason.SERVER_ERROR);
            case RATE_LIMIT_EXCEEDED_CODE -> Optional.of(TerminalReason.RATE_LIMIT_EXCEEDED);
            default -> Optional.empty();
        });
    }

    private static Optional<TerminalReason> terminalReasonForIncompleteReason(String incompleteReason) {
        return switch (incompleteReason) {
            case MAX_OUTPUT_TOKENS_REASON -> Optional.of(TerminalReason.MAX_OUTPUT_TOKENS);
            case CONTENT_FILTER_REASON -> Optional.of(TerminalReason.CONTENT_FILTER);
            default -> Optional.empty();
        };
    }

    private static String messageForError(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case SERVER_ERROR -> "Provider stream reported a terminal server error";
            case RATE_LIMIT_EXCEEDED -> "Provider stream reported a terminal rate-limit error";
            default -> "Provider stream reported a terminal error";
        };
    }

    private static String messageForFailure(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case SERVER_ERROR -> "Provider stream reported a failed response after a server error";
            case RATE_LIMIT_EXCEEDED -> "Provider stream reported a failed response after rate limiting";
            default -> "Provider stream reported a failed response";
        };
    }

    private static String messageForIncompleteResponse(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case MAX_OUTPUT_TOKENS -> "Provider stream stopped after reaching the output-token limit";
            case CONTENT_FILTER -> "Provider stream stopped because the response was filtered";
            default -> "Provider stream reported an incomplete response";
        };
    }
}
