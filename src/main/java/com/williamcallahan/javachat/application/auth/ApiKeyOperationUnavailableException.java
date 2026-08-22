package com.williamcallahan.javachat.application.auth;

/** Signals that the identity provider could not complete an API-key operation. */
public final class ApiKeyOperationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an unavailable operation with its provider cause.
     *
     * @param reason operator-facing failure reason
     * @param cause provider failure
     */
    public ApiKeyOperationUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }

    /**
     * Creates an unavailable operation without a separate provider cause.
     *
     * @param reason operator-facing failure reason
     */
    public ApiKeyOperationUnavailableException(String reason) {
        super(reason);
    }
}
