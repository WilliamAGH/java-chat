package com.williamcallahan.javachat.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;

/**
 * Bounds whole-search concurrency within the existing Qdrant query deadline.
 */
final class QdrantSearchAdmission {
    private static final int MAX_CONCURRENT_SEARCH_OPERATIONS = 1;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_SEARCH_OPERATIONS, true);

    <T> T execute(Duration queryTimeout, List<String> collectionNames, LongFunction<T> admittedSearch) {
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        List<String> requiredCollectionNames = List.copyOf(Objects.requireNonNull(collectionNames, "collectionNames"));
        if (requiredCollectionNames.isEmpty()) {
            throw new IllegalArgumentException("collectionNames cannot be empty");
        }
        Objects.requireNonNull(admittedSearch, "admittedSearch");
        long queryDeadlineNanos = System.nanoTime() + queryTimeout.toNanos();
        long remainingAdmissionNanos = Math.max(0L, queryDeadlineNanos - System.nanoTime());
        boolean admitted;
        try {
            admitted = permits.tryAcquire(remainingAdmissionNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw admissionFailure(
                    requiredCollectionNames,
                    "Interrupted",
                    "Qdrant search admission was interrupted",
                    HybridSearchPartialFailureException.FailureDisposition.PERMANENT,
                    interruptedException);
        }
        if (!admitted) {
            TimeoutException admissionTimeout = new TimeoutException(
                    "Qdrant search admission exhausted the shared " + queryTimeout.toMillis() + "ms query budget");
            throw admissionFailure(
                    requiredCollectionNames,
                    "AdmissionTimeout",
                    admissionTimeout.getMessage(),
                    HybridSearchPartialFailureException.FailureDisposition.TRANSIENT,
                    admissionTimeout);
        }
        try {
            return admittedSearch.apply(queryDeadlineNanos);
        } finally {
            permits.release();
        }
    }

    private static HybridSearchPartialFailureException admissionFailure(
            List<String> collectionNames,
            String failureType,
            String failureDetails,
            HybridSearchPartialFailureException.FailureDisposition failureDisposition,
            Throwable dependencyFailure) {
        List<HybridSearchPartialFailureException.CollectionSearchFailure> admissionFailures = collectionNames.stream()
                .distinct()
                .map(collectionName -> new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collectionName, failureType, failureDetails, failureDisposition))
                .toList();
        return new HybridSearchPartialFailureException(
                "Qdrant search admission failed", admissionFailures, List.of(dependencyFailure));
    }
}
