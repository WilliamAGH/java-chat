package com.williamcallahan.javachat.service.markdown;

/**
 * Signals a failure while processing markdown with the AST-based pipeline.
 */
public class MarkdownProcessingException extends IllegalStateException {

    /**
     * Creates a markdown processing exception with context and root cause.
     *
     * @param message failure summary
     * @param cause the underlying failure
     */
    public MarkdownProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
