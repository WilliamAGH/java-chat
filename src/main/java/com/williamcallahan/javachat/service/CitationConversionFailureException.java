package com.williamcallahan.javachat.service;

import java.io.Serial;

/**
 * Signals that a citation response would omit one or more discovered source documents.
 *
 * <p>The failed conversion count makes the partial-result condition observable without exposing
 * malformed source metadata to API consumers.</p>
 */
public final class CitationConversionFailureException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final String CITATION_CONVERSION_FAILURE_MESSAGE_PREFIX = "Citation conversion failed for ";
    private static final String CITATION_CONVERSION_FAILURE_MESSAGE_SUFFIX = " source document(s)";

    private final int failedConversionCount;

    /**
     * Creates a strict failure for a nonzero number of unconvertible source documents.
     *
     * @param failedConversionCount number of source documents that failed conversion
     * @throws IllegalArgumentException when the failure count is not positive
     */
    public CitationConversionFailureException(int failedConversionCount) {
        super(messageFor(failedConversionCount));
        this.failedConversionCount = failedConversionCount;
    }

    /**
     * Returns the number of source documents omitted by the failed conversion.
     *
     * @return positive source-document conversion failure count
     */
    public int failedConversionCount() {
        return failedConversionCount;
    }

    private static String messageFor(int failedConversionCount) {
        if (failedConversionCount < 1) {
            throw new IllegalArgumentException("failedConversionCount must be positive");
        }
        return CITATION_CONVERSION_FAILURE_MESSAGE_PREFIX
                + failedConversionCount
                + CITATION_CONVERSION_FAILURE_MESSAGE_SUFFIX;
    }
}
