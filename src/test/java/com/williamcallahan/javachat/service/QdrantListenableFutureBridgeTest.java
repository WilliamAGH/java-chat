package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies Qdrant future completion and cancellation cross the Guava-to-JDK boundary unchanged. */
class QdrantListenableFutureBridgeTest {

    @Test
    void mirrorsSuccessfulCompletion() {
        SettableFuture<String> qdrantQueryFuture = SettableFuture.create();
        CompletableFuture<String> bridgedQueryFuture =
                QdrantListenableFutureBridge.toCompletableFuture(qdrantQueryFuture);

        qdrantQueryFuture.set("completed query");

        assertEquals("completed query", bridgedQueryFuture.join());
    }

    @Test
    void mirrorsExceptionalCompletion() {
        SettableFuture<String> qdrantQueryFuture = SettableFuture.create();
        CompletableFuture<String> bridgedQueryFuture =
                QdrantListenableFutureBridge.toCompletableFuture(qdrantQueryFuture);
        IllegalStateException queryFailure = new IllegalStateException("query failed");

        qdrantQueryFuture.setException(queryFailure);

        CompletionException completionFailure = assertThrows(CompletionException.class, bridgedQueryFuture::join);
        assertSame(queryFailure, completionFailure.getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesCancellationAndItsInterruptFlagToTheQdrantFuture(boolean mayInterruptIfRunning) {
        SettableFuture<String> qdrantQueryFuture = spy(SettableFuture.create());
        CompletableFuture<String> bridgedQueryFuture =
                QdrantListenableFutureBridge.toCompletableFuture(qdrantQueryFuture);

        assertTrue(bridgedQueryFuture.cancel(mayInterruptIfRunning));

        verify(qdrantQueryFuture).cancel(mayInterruptIfRunning);
        assertTrue(bridgedQueryFuture.isCancelled());
    }
}
