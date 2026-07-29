package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import java.io.Serial;
import java.util.Optional;

/**
 * Preserves finite Responses API terminal states for retry and backoff decisions.
 *
 * <p>Provider messages are deliberately excluded because terminal events may contain
 * request-specific or sensitive text. Only protocol-defined codes and reasons that affect
 * application behavior are retained.</p>
 */
final class OpenAiResponseException extends OpenAIException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Identifies only the protocol terminal reasons that change application behavior. */
    enum TerminalReason {
        SERVER_ERROR,
        RATE_LIMIT_EXCEEDED,
        ERROR,
        FAILED,
        CANCELLED,
        MAX_OUTPUT_TOKENS,
        CONTENT_FILTER,
        INCOMPLETE,
        MISSING_COMPLETION,
        NO_VISIBLE_TEXT
    }

    private final TerminalReason terminalReason;

    private OpenAiResponseException(String message, TerminalReason terminalReason) {
        super(message);
        this.terminalReason = terminalReason;
    }

    static OpenAiResponseException error(Optional<ResponseError.Code> providerCode) {
        TerminalReason terminalReason = terminalReasonForErrorCode(providerCode).orElse(TerminalReason.ERROR);
        return new OpenAiResponseException(messageForError(terminalReason), terminalReason);
    }

    static OpenAiResponseException failed(Optional<ResponseError.Code> providerCode) {
        TerminalReason terminalReason = terminalReasonForErrorCode(providerCode).orElse(TerminalReason.FAILED);
        return new OpenAiResponseException(messageForFailure(terminalReason), terminalReason);
    }

    static OpenAiResponseException incomplete(Optional<Response.IncompleteDetails.Reason> incompleteReason) {
        TerminalReason terminalReason = incompleteReason
                .flatMap(OpenAiResponseException::terminalReasonForIncompleteReason)
                .orElse(TerminalReason.INCOMPLETE);
        return new OpenAiResponseException(messageForIncompleteResponse(terminalReason), terminalReason);
    }

    static OpenAiResponseException cancelled() {
        return new OpenAiResponseException("Provider response was cancelled", TerminalReason.CANCELLED);
    }

    static OpenAiResponseException missingCompletion() {
        return new OpenAiResponseException(
                "Provider response ended before response.completed", TerminalReason.MISSING_COMPLETION);
    }

    static OpenAiResponseException withoutVisibleText() {
        return new OpenAiResponseException(
                "Provider response completed without visible text", TerminalReason.NO_VISIBLE_TEXT);
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

    private static Optional<TerminalReason> terminalReasonForErrorCode(Optional<ResponseError.Code> providerCode) {
        return providerCode.flatMap(code -> switch (code) {
            case ResponseError.Code providerErrorCode
            when ResponseError.Code.SERVER_ERROR.equals(providerErrorCode) -> Optional.of(TerminalReason.SERVER_ERROR);
            case ResponseError.Code providerErrorCode
            when ResponseError.Code.RATE_LIMIT_EXCEEDED.equals(providerErrorCode) ->
                Optional.of(TerminalReason.RATE_LIMIT_EXCEEDED);
            default -> Optional.empty();
        });
    }

    private static Optional<TerminalReason> terminalReasonForIncompleteReason(
            Response.IncompleteDetails.Reason incompleteReason) {
        return switch (incompleteReason) {
            case Response.IncompleteDetails.Reason providerReason
            when Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS.equals(providerReason) ->
                Optional.of(TerminalReason.MAX_OUTPUT_TOKENS);
            case Response.IncompleteDetails.Reason providerReason
            when Response.IncompleteDetails.Reason.CONTENT_FILTER.equals(providerReason) ->
                Optional.of(TerminalReason.CONTENT_FILTER);
            default -> Optional.empty();
        };
    }

    private static String messageForError(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case SERVER_ERROR -> "Provider response reported a terminal server error";
            case RATE_LIMIT_EXCEEDED -> "Provider response reported a terminal rate-limit error";
            default -> "Provider response reported a terminal error";
        };
    }

    private static String messageForFailure(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case SERVER_ERROR -> "Provider reported a failed response after a server error";
            case RATE_LIMIT_EXCEEDED -> "Provider reported a failed response after rate limiting";
            default -> "Provider reported a failed response";
        };
    }

    private static String messageForIncompleteResponse(TerminalReason terminalReason) {
        return switch (terminalReason) {
            case MAX_OUTPUT_TOKENS -> "Provider response stopped after reaching the output-token limit";
            case CONTENT_FILTER -> "Provider response stopped because it was filtered";
            default -> "Provider reported an incomplete response";
        };
    }
}
