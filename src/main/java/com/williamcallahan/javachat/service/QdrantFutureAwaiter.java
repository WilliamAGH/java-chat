package com.williamcallahan.javachat.service;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
}
