package com.williamcallahan.javachat.service;

import java.util.Objects;

/**
 * Structured runtime notice emitted during streaming provider fallback.
 *
 * <p>Construction uses the fluent {@link #builder(String, String)} API to avoid
 * positional parameter ambiguity. Provider-attempt context is captured in
 * {@link StreamingNoticeOrigin} to group the data clump that always travels together.</p>
 *
 * @param summary short user-facing summary
 * @param diagnosticContext detailed context for UI status indicators
 * @param code stable machine-readable status code
 * @param retryable whether this notice represents a retryable condition
 * @param origin provider and attempt context that produced this notice
 */
public record StreamingNotice(
        String summary, String diagnosticContext, String code, boolean retryable, StreamingNoticeOrigin origin) {
    public StreamingNotice {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary cannot be null or blank");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code cannot be null or blank");
        }
        Objects.requireNonNull(origin, "origin");
        diagnosticContext = diagnosticContext == null ? "" : diagnosticContext;
    }

    /** Convenience delegate: provider display name from origin. */
    public String provider() {
        return origin.provider();
    }

    /** Convenience delegate: processing stage from origin. */
    public String stage() {
        return origin.stage();
    }

    /** Convenience delegate: 1-based attempt number from origin. */
    public int attempt() {
        return origin.attempt();
    }

    /** Convenience delegate: max configured attempts from origin. */
    public int maxAttempts() {
        return origin.maxAttempts();
    }

    /** Creates a builder with the required notice identification fields. */
    public static Builder builder(String summary, String code) {
        return new Builder(summary, code);
    }

    /**
     * Fluent builder that avoids positional construction of the notice fields.
     * Required fields are supplied at builder creation; optional fields have safe defaults.
     */
    public static final class Builder {
        private final String summary;
        private final String code;
        private String diagnosticContext = "";
        private boolean retryable;
        private StreamingNoticeOrigin origin;

        private Builder(String summary, String code) {
            this.summary = summary;
            this.code = code;
        }

        /** Sets the verbose diagnostic context for UI display. */
        public Builder diagnosticContext(String diagnosticContext) {
            this.diagnosticContext = diagnosticContext;
            return this;
        }

        /** Marks whether the condition is retryable by the client. */
        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        /** Sets the provider-attempt origin context. */
        public Builder origin(StreamingNoticeOrigin origin) {
            this.origin = origin;
            return this;
        }

        /** Builds the immutable notice record. */
        public StreamingNotice build() {
            return new StreamingNotice(summary, diagnosticContext, code, retryable, origin);
        }
    }
}
