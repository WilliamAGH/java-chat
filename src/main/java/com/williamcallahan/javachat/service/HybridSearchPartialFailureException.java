package com.williamcallahan.javachat.service;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

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
        super(message);
        this.collectionFailures = List.copyOf(Objects.requireNonNull(collectionFailures, "collectionFailures"));
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
     * Captures one collection query failure during hybrid fan-out.
     *
     * @param collectionName collection name that failed
     * @param failureType normalized failure type
     * @param failureDetails compact failure details
     */
    public record CollectionSearchFailure(String collectionName, String failureType, String failureDetails)
            implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public CollectionSearchFailure {
            collectionName = sanitize(collectionName);
            failureType = sanitize(failureType);
            failureDetails = sanitize(failureDetails);
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
}
