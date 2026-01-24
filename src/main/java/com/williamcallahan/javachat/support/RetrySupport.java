package com.williamcallahan.javachat.support;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple retry utility for transient failures without external dependencies.
 *
 * <p>Provides exponential backoff retry for operations that may fail transiently.
 * Only retries when the error classifier determines the failure is transient.
 */
public final class RetrySupport {

    private static final Logger log = LoggerFactory.getLogger(RetrySupport.class);

    /** Default maximum retry attempts. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Default initial backoff duration. */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    /** Default backoff multiplier. */
    public static final double DEFAULT_MULTIPLIER = 2.0;
    /** Maximum backoff duration to prevent excessive waits. */
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private RetrySupport() {}

    /**
     * Executes a supplier with retry for transient failures.
     *
     * @param operation the operation to execute
     * @param operationName name for logging purposes
     * @param <T> return type
     * @return the result of the operation
     * @throws RuntimeException if all retries are exhausted or a non-transient error occurs
     */
    public static <T> T executeWithRetry(Supplier<T> operation, String operationName) {
        return executeWithRetry(operation, operationName, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF);
    }

    /**
     * Executes a supplier with configurable retry for transient failures.
     *
     * @param operation the operation to execute
     * @param operationName name for logging purposes
     * @param maxAttempts maximum number of attempts
     * @param initialBackoff initial backoff duration
     * @param <T> return type
     * @return the result of the operation
     * @throws RuntimeException if all retries are exhausted or a non-transient error occurs
     */
    public static <T> T executeWithRetry(
            Supplier<T> operation, 
            String operationName,
            int maxAttempts,
            Duration initialBackoff) {
        
        RuntimeException lastException = null;
        Duration currentBackoff = initialBackoff;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                lastException = exception;
                
                // Check if this is a transient error worth retrying
                if (!RetrievalErrorClassifier.isTransientVectorStoreError(exception)) {
                    log.warn("{} failed with non-transient error on attempt {}/{}, not retrying",
                        operationName, attempt, maxAttempts);
                    throw exception;
                }
                
                if (attempt < maxAttempts) {
                    log.warn("{} failed with transient error on attempt {}/{}, retrying in {}ms",
                        operationName, attempt, maxAttempts, currentBackoff.toMillis());
                    
                    try {
                        Thread.sleep(currentBackoff.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Retry interrupted", interrupted);
                    }
                    
                    // Exponential backoff with cap
                    long nextBackoffMillis = (long) (currentBackoff.toMillis() * DEFAULT_MULTIPLIER);
                    currentBackoff = Duration.ofMillis(Math.min(nextBackoffMillis, MAX_BACKOFF.toMillis()));
                } else {
                    log.error("{} failed after {} attempts, giving up", operationName, maxAttempts);
                }
            }
        }
        
        throw lastException;
    }

    /**
     * Executes a runnable with retry for transient failures.
     *
     * @param operation the operation to execute
     * @param operationName name for logging purposes
     */
    public static void executeWithRetry(Runnable operation, String operationName) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, operationName);
    }
}
