package com.williamcallahan.javachat.application.knowledge;

/** Reports that the backing store could not produce a complete knowledge inventory. */
public final class KnowledgeInventoryUnavailableException extends RuntimeException {
    /** Preserves the backing-store cause for diagnostics while exposing a stable boundary failure. */
    public KnowledgeInventoryUnavailableException(Throwable cause) {
        super("Knowledge inventory is temporarily unavailable", cause);
    }
}
