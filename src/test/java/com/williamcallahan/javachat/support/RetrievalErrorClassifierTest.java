package com.williamcallahan.javachat.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Verifies typed timeout and gRPC status classification remains aligned with retry behavior. */
class RetrievalErrorClassifierTest {

    private static final String QDRANT_CLIENT_TIMEOUT_RETRY_OPERATION = "Qdrant client timeout retry";
    private static final int SUCCESSFUL_RETRY_ATTEMPT_COUNT = 2;
    private static final Logger RETRY_SUPPORT_LOGGER = (Logger) LoggerFactory.getLogger(RetrySupport.class);

    @Test
    void classifiesDirectTimeoutExceptionAsTransient() {
        TimeoutException directTimeoutException = new TimeoutException("Future took too long");

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(directTimeoutException));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(directTimeoutException));
    }

    @Test
    void classifiesWrappedTimeoutExceptionAsTransient() {
        IllegalStateException qdrantTimeoutFailure = new IllegalStateException(
                "Qdrant operation timed out after 5s", new TimeoutException("Future did not complete"));

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(qdrantTimeoutFailure));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(qdrantTimeoutFailure));
    }

    @Test
    void classifiesNullMessageTimeoutExceptionAsTransient() {
        TimeoutException nullMessageTimeoutException = new TimeoutException();

        assertNull(nullMessageTimeoutException.getMessage());
        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(nullMessageTimeoutException));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(nullMessageTimeoutException));
    }

    @Test
    void doesNotClassifyTimeoutLookalikeTextAsTransient() {
        IllegalStateException timeoutConfigurationFailure =
                new IllegalStateException("Qdrant timeout configuration is invalid");

        assertEquals("Unknown Error", RetrievalErrorClassifier.determineErrorType(timeoutConfigurationFailure));
        assertFalse(RetrievalErrorClassifier.isTransientVectorStoreError(timeoutConfigurationFailure));
    }

    @Test
    void classifiesGrpcDeadlineExceededAsTransient() {
        RuntimeException grpcDeadlineFailure = Status.DEADLINE_EXCEEDED.asRuntimeException();

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(grpcDeadlineFailure));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(grpcDeadlineFailure));
    }

    @Test
    void classifiesGrpcUnavailableAsTransient() {
        RuntimeException grpcUnavailableFailure = Status.UNAVAILABLE.asRuntimeException();

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(grpcUnavailableFailure));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(grpcUnavailableFailure));
    }

    @Test
    void doesNotRetryGrpcResourceExhaustedFailure() {
        RuntimeException grpcResourceExhaustedFailure = Status.RESOURCE_EXHAUSTED.asRuntimeException();

        assertEquals("Unknown Error", RetrievalErrorClassifier.determineErrorType(grpcResourceExhaustedFailure));
        assertFalse(RetrievalErrorClassifier.isTransientVectorStoreError(grpcResourceExhaustedFailure));
    }

    @Test
    void prioritizesGrpcUnavailableOverHttp429Description() {
        RuntimeException grpcUnavailableFailure = Status.UNAVAILABLE
                .withDescription("HTTP 429 from upstream proxy")
                .asRuntimeException();

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(grpcUnavailableFailure));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(grpcUnavailableFailure));
    }

    @Test
    void keepsGrpcResourceExhaustedWithHttp429DescriptionNonRetryable() {
        RuntimeException grpcResourceExhaustedFailure = Status.RESOURCE_EXHAUSTED
                .withDescription("HTTP 429 quota exhausted")
                .asRuntimeException();

        assertEquals("Unknown Error", RetrievalErrorClassifier.determineErrorType(grpcResourceExhaustedFailure));
        assertFalse(RetrievalErrorClassifier.isTransientVectorStoreError(grpcResourceExhaustedFailure));
    }

    @Test
    void doesNotRetryGrpcInvalidArgumentFailure() {
        RuntimeException grpcInvalidArgumentFailure = Status.INVALID_ARGUMENT.asRuntimeException();

        assertEquals("Unknown Error", RetrievalErrorClassifier.determineErrorType(grpcInvalidArgumentFailure));
        assertFalse(RetrievalErrorClassifier.isTransientVectorStoreError(grpcInvalidArgumentFailure));
    }

    @Test
    void classifiesWrappedGrpcStatusExceptionAsTransient() {
        IllegalStateException wrappedGrpcFailure =
                new IllegalStateException("Qdrant operation failed", Status.UNAVAILABLE.asException());

        assertEquals("Connection Error", RetrievalErrorClassifier.determineErrorType(wrappedGrpcFailure));
        assertTrue(RetrievalErrorClassifier.isTransientVectorStoreError(wrappedGrpcFailure));
    }

    @Test
    void retriesWrappedClientTimeoutFailure() {
        AtomicInteger operationAttemptCount = new AtomicInteger();

        String operationOutcome;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(RETRY_SUPPORT_LOGGER)) {
            operationOutcome = RetrySupport.executeWithRetry(
                    () -> {
                        if (operationAttemptCount.getAndIncrement() == 0) {
                            throw new IllegalStateException(
                                    "Qdrant operation timed out after 5s", new TimeoutException());
                        }
                        return "completed";
                    },
                    QDRANT_CLIENT_TIMEOUT_RETRY_OPERATION,
                    RetrySupport.DEFAULT_MAX_ATTEMPTS,
                    Duration.ZERO);

            assertEquals(1, expectedLogEvents.events().size());
            var retryWarning = expectedLogEvents.events().getFirst();
            assertEquals(Level.WARN, retryWarning.getLevel());
            assertEquals(
                    "Qdrant client timeout retry failed with transient error on attempt 1/3, retrying in 0ms",
                    retryWarning.getFormattedMessage());
            assertNull(retryWarning.getThrowableProxy());
        }

        assertEquals("completed", operationOutcome);
        assertEquals(SUCCESSFUL_RETRY_ATTEMPT_COUNT, operationAttemptCount.get());
    }
}
