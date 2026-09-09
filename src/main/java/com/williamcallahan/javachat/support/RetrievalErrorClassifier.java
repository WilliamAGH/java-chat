package com.williamcallahan.javachat.support;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Classifies retrieval errors and logs user-facing context without leaking raw error payloads.
 */
public final class RetrievalErrorClassifier {

    /** Owns the vector-store retry policy for each failure category. */
    private enum RetrievalErrorCategory {
        NOT_FOUND(false),
        UNAUTHORIZED(false),
        FORBIDDEN(false),
        RATE_LIMITED(true),
        CONNECTION_ERROR(true),
        EMBEDDING_SERVICE_UNAVAILABLE(false),
        UNKNOWN(false);

        private final boolean retryableVectorStoreFailure;

        RetrievalErrorCategory(boolean retryableVectorStoreFailure) {
            this.retryableVectorStoreFailure = retryableVectorStoreFailure;
        }
    }

    private RetrievalErrorClassifier() {}

    private static RetrievalErrorCategory classify(Throwable failure) {
        return classifyGrpcStatus(failure).orElseGet(() -> classifyNonGrpcFailure(failure));
    }

    private static RetrievalErrorCategory classifyNonGrpcFailure(Throwable failure) {
        StringBuilder failureMessageBuilder = new StringBuilder();
        boolean timeoutExceptionFound = false;
        Throwable failureInChain = failure;
        while (failureInChain != null) {
            timeoutExceptionFound |= failureInChain instanceof TimeoutException;

            String currentFailureMessage = failureInChain.getMessage();
            if (currentFailureMessage != null && !currentFailureMessage.isBlank()) {
                if (failureMessageBuilder.length() > 0) {
                    failureMessageBuilder.append(' ');
                }
                failureMessageBuilder.append(currentFailureMessage);
            }
            failureInChain = failureInChain.getCause();
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
        } else if (timeoutExceptionFound || combinedFailureMessage.contains("connection")) {
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
     * <p>Transient errors include connection issues, typed timeouts, and gRPC {@link
     * Status.Code#DEADLINE_EXCEEDED}/{@link Status.Code#UNAVAILABLE} statuses. Non-transient errors like
     * quota exhaustion, invalid UUID format, or programming errors should not be retried.
     *
     * @param failure exception to classify
     * @return true if the failure is transient and a retry is appropriate
     */
    public static boolean isTransientVectorStoreError(Throwable failure) {
        return classify(failure).retryableVectorStoreFailure;
    }

    /**
     * Classifies an explicit gRPC status before inspecting arbitrary exception text.
     *
     * <p>{@link Status#fromThrowable(Throwable)} traverses wrapped {@link StatusException} and {@link
     * StatusRuntimeException} instances. A typed {@link Status.Code#RESOURCE_EXHAUSTED} must not become a
     * retryable HTTP-like 429 merely because its description contains that text.</p>
     */
    private static Optional<RetrievalErrorCategory> classifyGrpcStatus(Throwable failure) {
        if (!containsGrpcStatusException(failure)) {
            return Optional.empty();
        }

        Status.Code grpcStatusCode = Status.fromThrowable(failure).getCode();
        return Optional.of(
                switch (grpcStatusCode) {
                    case DEADLINE_EXCEEDED, UNAVAILABLE -> RetrievalErrorCategory.CONNECTION_ERROR;
                    default -> RetrievalErrorCategory.UNKNOWN;
                });
    }

    private static boolean containsGrpcStatusException(Throwable failure) {
        Throwable failureInChain = failure;
        while (failureInChain != null) {
            if (failureInChain instanceof StatusException || failureInChain instanceof StatusRuntimeException) {
                return true;
            }
            failureInChain = failureInChain.getCause();
        }
        return false;
    }
}
