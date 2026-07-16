package com.williamcallahan.javachat.support;

import io.grpc.Status;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;

/**
 * Classifies retrieval errors and logs user-facing context without leaking raw error payloads.
 */
public final class RetrievalErrorClassifier {

    /** Owns the stable diagnostic label and vector-store retry policy for each failure category. */
    private enum RetrievalErrorCategory {
        NOT_FOUND("404 Not Found", false),
        UNAUTHORIZED("401 Unauthorized", false),
        FORBIDDEN("403 Forbidden", false),
        RATE_LIMITED("429 Rate Limited", true),
        CONNECTION_ERROR("Connection Error", true),
        EMBEDDING_SERVICE_UNAVAILABLE("Embedding Service Unavailable", false),
        UNKNOWN("Unknown Error", false);

        private final String errorLabel;
        private final boolean retryableVectorStoreFailure;

        RetrievalErrorCategory(String errorLabel, boolean retryableVectorStoreFailure) {
            this.errorLabel = errorLabel;
            this.retryableVectorStoreFailure = retryableVectorStoreFailure;
        }
    }

    private RetrievalErrorClassifier() {}

    /**
     * Determines a stable error category from exception types, messages, and causes.
     *
     * @param failure failure encountered during retrieval
     * @return normalized error category label
     */
    public static String determineErrorType(Throwable failure) {
        return classify(failure).errorLabel;
    }

    private static RetrievalErrorCategory classify(Throwable failure) {
        StringBuilder failureMessageBuilder = new StringBuilder();
        boolean timeoutExceptionFound = false;
        boolean retryableGrpcStatusFound = isRetryableGrpcStatus(failure);
        Throwable current = failure;
        while (current != null) {
            timeoutExceptionFound |= current instanceof TimeoutException;

            String currentFailureMessage = current.getMessage();
            if (currentFailureMessage != null && !currentFailureMessage.isBlank()) {
                if (failureMessageBuilder.length() > 0) {
                    failureMessageBuilder.append(' ');
                }
                failureMessageBuilder.append(currentFailureMessage);
            }
            current = current.getCause();
        }

        String combinedFailureMessage = failureMessageBuilder.toString().toLowerCase(Locale.ROOT);

        if (combinedFailureMessage.contains("404") || combinedFailureMessage.contains("not found")) {
            return RetrievalErrorCategory.NOT_FOUND;
        } else if (combinedFailureMessage.contains("401") || combinedFailureMessage.contains("unauthorized")) {
            return RetrievalErrorCategory.UNAUTHORIZED;
        } else if (combinedFailureMessage.contains("403") || combinedFailureMessage.contains("forbidden")) {
            return RetrievalErrorCategory.FORBIDDEN;
        } else if (combinedFailureMessage.contains("429") || combinedFailureMessage.contains("too many requests")) {
            return RetrievalErrorCategory.RATE_LIMITED;
        } else if (timeoutExceptionFound || retryableGrpcStatusFound || combinedFailureMessage.contains("connection")) {
            return RetrievalErrorCategory.CONNECTION_ERROR;
        } else if (combinedFailureMessage.contains("embedding")
                && (combinedFailureMessage.contains("unavailable")
                        || combinedFailureMessage.contains("unreachable")
                        || combinedFailureMessage.contains("provider"))) {
            return RetrievalErrorCategory.EMBEDDING_SERVICE_UNAVAILABLE;
        }
        return RetrievalErrorCategory.UNKNOWN;
    }

    /**
     * Determines whether the failure is a transient Qdrant/vector store error that should be retried.
     *
     * <p>Transient errors include connection issues, timeouts, and service unavailability (503).
     * Non-transient errors like invalid UUID format or programming errors should NOT be retried
     * as they indicate bugs that need fixing.
     *
     * @param failure exception to classify
     * @return true if the failure is transient and a retry is appropriate
     */
    public static boolean isTransientVectorStoreError(Throwable failure) {
        return classify(failure).retryableVectorStoreFailure;
    }

    /**
     * Identifies gRPC transport failures eligible for the shared vector-store retry path.
     *
     * <p>{@link Status#fromThrowable(Throwable)} locates wrapped gRPC status exceptions.
     * {@link Status.Code#RESOURCE_EXHAUSTED} remains non-retryable because it can represent
     * quota or capacity pressure rather than a transport interruption.</p>
     */
    private static boolean isRetryableGrpcStatus(Throwable failure) {
        if (failure == null) {
            return false;
        }

        Status.Code grpcStatusCode = Status.fromThrowable(failure).getCode();
        return switch (grpcStatusCode) {
            case DEADLINE_EXCEEDED, UNAVAILABLE -> true;
            default -> false;
        };
    }

    /**
     * Log user-friendly context about why vector search failed.
     *
     * @param log logger to emit messages
     * @param errorType classified error category
     * @param error original exception
     */
    public static void logUserFriendlyErrorContext(Logger log, String errorType, Throwable error) {
        if (error.getCause() instanceof com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException) {
            log.info(
                    "Embedding services are unavailable. Using keyword-based search with limited semantic understanding.");
        } else if (errorType.contains("404")) {
            log.info("Embedding API endpoint not found. Check configuration for spring.ai.openai.embedding.base-url");
        } else if (errorType.contains("401") || errorType.contains("403")) {
            log.info("Embedding API authentication failed. Check OPENAI_API_KEY or GITHUB_TOKEN configuration");
        } else if (errorType.contains("429")) {
            log.info("Embedding API rate limit exceeded. Consider using local embeddings or upgrading API tier");
        }
    }
}
