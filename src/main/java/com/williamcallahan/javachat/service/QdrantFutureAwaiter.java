package com.williamcallahan.javachat.service;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits for Qdrant futures and normalizes asynchronous failures for service callers.
 *
 * <p>Consolidating timeout and interruption handling keeps retry wrappers simple and
 * consistently preserves interrupt status for upstream execution contexts.</p>
 */
final class QdrantFutureAwaiter {

    private QdrantFutureAwaiter() {}

    static <T> T awaitFuture(ListenableFuture<T> future, long timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant operation interrupted", interrupted);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause == null) {
                throw new IllegalStateException("Qdrant operation failed", executionException);
            }
            throw new IllegalStateException("Qdrant operation failed", cause);
        } catch (TimeoutException timeoutException) {
            throw new IllegalStateException(
                    "Qdrant operation timed out after " + timeoutSeconds + "s", timeoutException);
        }
    }

    static <T> T awaitFuture(CompletableFuture<T> future, long timeoutNanos) {
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interruptedFailure) {
            Thread.currentThread().interrupt();
            throw QdrantFutureAwaitException.interrupted(interruptedFailure);
        } catch (ExecutionException executionFailure) {
            Throwable dependencyFailure = executionFailure.getCause();
            throw QdrantFutureAwaitException.dependency(
                    normalizeDependencyFailure(dependencyFailure == null ? executionFailure : dependencyFailure));
        } catch (TimeoutException timeoutFailure) {
            throw QdrantFutureAwaitException.timedOut(timeoutFailure);
        }
    }

    static <T> CompletableFuture<T> exhaustedQueryBudgetFuture() {
        return CompletableFuture.failedFuture(
                new TimeoutException("Query budget was exhausted before this collection was dispatched"));
    }

    private static Throwable normalizeDependencyFailure(Throwable dependencyFailure) {
        if (Status.fromThrowable(dependencyFailure).getCode() != Status.Code.DEADLINE_EXCEEDED) {
            return dependencyFailure;
        }
        TimeoutException timeoutFailure = new TimeoutException("Qdrant gRPC deadline exceeded");
        timeoutFailure.initCause(dependencyFailure);
        return timeoutFailure;
    }

    /** Preserves the typed disposition of a failed CompletableFuture wait. */
    static final class QdrantFutureAwaitException extends RuntimeException {
        private final AwaitDisposition awaitDisposition;

        private QdrantFutureAwaitException(AwaitDisposition awaitDisposition, Throwable cause) {
            super(cause);
            this.awaitDisposition = awaitDisposition;
        }

        private static QdrantFutureAwaitException interrupted(Throwable cause) {
            return new QdrantFutureAwaitException(AwaitDisposition.INTERRUPTED, cause);
        }

        private static QdrantFutureAwaitException dependency(Throwable cause) {
            return new QdrantFutureAwaitException(AwaitDisposition.DEPENDENCY_FAILURE, cause);
        }

        private static QdrantFutureAwaitException timedOut(Throwable cause) {
            return new QdrantFutureAwaitException(AwaitDisposition.TIMED_OUT, cause);
        }

        boolean interrupted() {
            return awaitDisposition == AwaitDisposition.INTERRUPTED;
        }

        boolean timedOut() {
            return awaitDisposition == AwaitDisposition.TIMED_OUT;
        }
    }

    /** Distinguishes dependency, timeout, and thread-interruption wait outcomes. */
    private enum AwaitDisposition {
        INTERRUPTED,
        DEPENDENCY_FAILURE,
        TIMED_OUT
    }
}
