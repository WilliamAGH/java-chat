package com.williamcallahan.javachat.service;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges Qdrant's Guava futures into CompletableFuture for fan-out orchestration.
 *
 * <p>Hybrid search aggregates many asynchronous collection queries and relies on
 * {@link CompletableFuture} composition APIs. This bridge keeps Guava interop in one place
 * so retrieval flow remains focused on query semantics.</p>
 *
 * <p>Uses Guava's {@link Futures#addCallback(ListenableFuture, FutureCallback, java.util.concurrent.Executor)
 * Futures.addCallback} with {@link MoreExecutors#directExecutor()} per the
 * <a href="https://github.com/google/guava/wiki/ListenableFutureExplained">ListenableFuture
 * Explained</a> guide. {@code directExecutor} is safe here because the callback performs only
 * lightweight {@link CompletableFuture#complete}/{@link CompletableFuture#completeExceptionally}
 * calls with no blocking. Guava 33.5.0-android provides no built-in
 * {@code ListenableFuture → CompletableFuture} converter (verified via source inspection
 * of {@code Futures.java}).</p>
 */
final class QdrantListenableFutureBridge {

    private QdrantListenableFutureBridge() {}

    /**
     * Converts a Guava listenable future into a completion-stage compatible future.
     *
     * @param qdrantQueryFuture asynchronous Qdrant query future
     * @param <T> successful completion type
     * @return completable future that mirrors success or failure from the source future
     */
    static <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> qdrantQueryFuture) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        Futures.addCallback(
                qdrantQueryFuture,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(T completedValue) {
                        completableFuture.complete(completedValue);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        completableFuture.completeExceptionally(throwable);
                    }
                },
                MoreExecutors.directExecutor());
        return completableFuture;
    }
}
