package com.williamcallahan.javachat.service;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Signals that one or more collection searches failed during hybrid retrieval fan-out.
 *
 * <p>This exception is raised in strict mode to prevent silent relevance degradation.</p>
 */
public class HybridSearchPartialFailureException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<CollectionSearchFailure> collectionFailures;

    /**
     * Creates a partial-failure exception with collection-specific failure details.
     *
     * @param message human-readable summary message
     * @param collectionFailures collection-scoped failures
     */
    public HybridSearchPartialFailureException(String message, List<CollectionSearchFailure> collectionFailures) {
        this(message, collectionFailures, List.of());
    }

    /**
     * Creates a partial-failure exception preserving typed dependency causes.
     *
     * @param message human-readable summary message
     * @param collectionFailures collection-scoped failures
     * @param dependencyFailures typed Qdrant failures in collection order
     */
    public HybridSearchPartialFailureException(
            String message,
            List<CollectionSearchFailure> collectionFailures,
            List<? extends Throwable> dependencyFailures) {
        super(message, firstFailure(dependencyFailures));
        this.collectionFailures = List.copyOf(Objects.requireNonNull(collectionFailures, "collectionFailures"));
        Throwable primaryFailure = firstFailure(dependencyFailures);
        dependencyFailures.stream()
                .skip(1)
                .filter(dependencyFailure -> !Objects.equals(dependencyFailure, primaryFailure))
                .forEach(this::addSuppressed);
    }

    /**
     * Returns the collection failures captured during fan-out.
     *
     * @return immutable collection failure list
     */
    public List<CollectionSearchFailure> collectionFailures() {
        return collectionFailures;
    }

    /**
     * Returns whether every failed collection reported a transient dependency disposition.
     *
     * @return true only when retrying can reasonably succeed
     */
    public boolean isRetryable() {
        return !collectionFailures.isEmpty()
                && collectionFailures.stream()
                        .allMatch(collectionFailure ->
                                collectionFailure.failureDisposition() == FailureDisposition.TRANSIENT);
    }

    /**
     * Classifies a typed Qdrant dependency failure for retry handling.
     *
     * @param dependencyFailure typed failure raised by the Qdrant gRPC client
     * @return retry disposition derived from the gRPC status
     */
    public static FailureDisposition classifyDependencyFailure(Throwable dependencyFailure) {
        if (dependencyFailure instanceof TimeoutException) {
            return FailureDisposition.TRANSIENT;
        }
        if (!(dependencyFailure instanceof StatusException) && !(dependencyFailure instanceof StatusRuntimeException)) {
            return FailureDisposition.PERMANENT;
        }
        return switch (Status.fromThrowable(dependencyFailure).getCode()) {
            case ABORTED, DEADLINE_EXCEEDED, INTERNAL, RESOURCE_EXHAUSTED, UNAVAILABLE -> FailureDisposition.TRANSIENT;
            default -> FailureDisposition.PERMANENT;
        };
    }

    /** Classifies a collection failure without parsing exception messages. */
    public enum FailureDisposition {
        /** A later request can reasonably succeed. */
        TRANSIENT,
        /** The same request must not be advertised as retryable. */
        PERMANENT
    }

    /**
     * Captures one collection query failure during hybrid fan-out.
     *
     * @param collectionName collection name that failed
     * @param failureType normalized failure type
     * @param failureDetails compact failure details
     * @param failureDisposition typed retry disposition
     */
    public record CollectionSearchFailure(
            String collectionName, String failureType, String failureDetails, FailureDisposition failureDisposition)
            implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public CollectionSearchFailure {
            collectionName = sanitize(collectionName);
            failureType = sanitize(failureType);
            failureDetails = sanitize(failureDetails);
            failureDisposition = Objects.requireNonNull(failureDisposition, "failureDisposition");
            if (collectionName.isBlank()) {
                throw new IllegalArgumentException("collectionName cannot be blank");
            }
            if (failureType.isBlank()) {
                throw new IllegalArgumentException("failureType cannot be blank");
            }
        }

        private static String sanitize(String rawValue) {
            if (rawValue == null) {
                return "";
            }
            String trimmedValue = rawValue.trim();
            return trimmedValue.isBlank() ? "" : trimmedValue;
        }
    }

    private static Throwable firstFailure(List<? extends Throwable> dependencyFailures) {
        Objects.requireNonNull(dependencyFailures, "dependencyFailures");
        return dependencyFailures.isEmpty() ? null : dependencyFailures.getFirst();
    }
}
