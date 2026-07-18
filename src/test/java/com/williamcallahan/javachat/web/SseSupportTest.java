package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.HEARTBEAT_INTERVAL_SECONDS;
import static com.williamcallahan.javachat.web.SseConstants.STREAM_BACKPRESSURE_BUFFER_CAPACITY;
import static com.williamcallahan.javachat.web.SseConstants.STREAM_CHUNK_COALESCE_MAX_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.subscriber.TestSubscriber;

/**
 * Verifies SSE stream preparation semantics used by streaming chat controllers.
 */
class SseSupportTest {

    private static final int BACKPRESSURE_OVERFLOW_BUFFER_MULTIPLIER = 2;
    private static final Duration BACKPRESSURE_TEST_COMPLETION_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void prepareDataStreamPublishesAllChunksToBothSubscribers() {
        SseSupport sseSupport = new SseSupport(new ObjectMapper());

        List<String> upstreamChunks = List.of("```java\n", "int x = 10;\n", "```");
        List<String> consumedChunks = new CopyOnWriteArrayList<>();
        Flux<String> preparedStream =
                sseSupport.prepareDataStream(Flux.fromIterable(upstreamChunks), consumedChunks::add);

        List<String> firstSubscriberChunks = new CopyOnWriteArrayList<>();
        List<String> secondSubscriberChunks = new CopyOnWriteArrayList<>();

        preparedStream.subscribe(firstSubscriberChunks::add);
        preparedStream.subscribe(secondSubscriberChunks::add);

        String expectedContent = String.join("", upstreamChunks);
        String firstSubscriberContent = String.join("", firstSubscriberChunks);
        String secondSubscriberContent = String.join("", secondSubscriberChunks);
        String consumedContent = String.join("", consumedChunks);

        assertEquals(expectedContent, firstSubscriberContent, "First subscriber should receive full stream content");
        assertEquals(expectedContent, secondSubscriberContent, "Second subscriber should receive full stream content");
        assertEquals(expectedContent, consumedContent, "Chunk consumer should observe full stream content");
    }

    @Test
    void prepareDataStreamTerminatesWithOverflowInsteadOfDroppingCoalescedChunks() {
        SseSupport sseSupport = new SseSupport(new ObjectMapper());
        int coalescedChunkCount = (STREAM_BACKPRESSURE_BUFFER_CAPACITY * BACKPRESSURE_OVERFLOW_BUFFER_MULTIPLIER) + 1;
        int rawChunkCount = coalescedChunkCount * STREAM_CHUNK_COALESCE_MAX_ITEMS;
        Flux<String> preparedStream = sseSupport.prepareDataStream(
                Flux.range(0, rawChunkCount).map(rawChunkIndex -> "chunk-" + rawChunkIndex), ignoredChunk -> {});
        TestSubscriber<String> textSubscriber =
                TestSubscriber.builder().initialRequest(0).build();
        TestSubscriber<String> heartbeatSubscriber =
                TestSubscriber.builder().initialRequest(0).build();

        preparedStream.subscribe(textSubscriber);
        preparedStream.subscribe(heartbeatSubscriber);

        textSubscriber.request(Long.MAX_VALUE);
        heartbeatSubscriber.request(Long.MAX_VALUE);
        textSubscriber.block(BACKPRESSURE_TEST_COMPLETION_TIMEOUT);
        heartbeatSubscriber.block(BACKPRESSURE_TEST_COMPLETION_TIMEOUT);

        List<String> deliveredChunks = textSubscriber.getReceivedOnNext();
        assertTrue(textSubscriber.isTerminatedError());
        assertTrue(heartbeatSubscriber.isTerminatedError());
        assertTrue(Exceptions.isOverflow(textSubscriber.expectTerminalError()));
        assertTrue(Exceptions.isOverflow(heartbeatSubscriber.expectTerminalError()));
        assertEquals(STREAM_BACKPRESSURE_BUFFER_CAPACITY, deliveredChunks.size());
        assertTrue(deliveredChunks.getFirst().startsWith("chunk-0"));
        assertEquals(deliveredChunks, heartbeatSubscriber.getReceivedOnNext());
    }

    @Test
    void cancellingTextAndHeartbeatStreamCancelsUpstream() {
        SseSupport sseSupport = new SseSupport(new ObjectMapper());
        AtomicInteger upstreamSubscriptionCount = new AtomicInteger();
        AtomicInteger upstreamCancellationCount = new AtomicInteger();
        Flux<String> upstreamStream = Flux.<String>never()
                .doOnSubscribe(ignoredSubscription -> upstreamSubscriptionCount.incrementAndGet())
                .doOnCancel(upstreamCancellationCount::incrementAndGet);
        Flux<String> preparedStream = sseSupport.prepareDataStream(upstreamStream, ignoredChunk -> {});
        Flux<ServerSentEvent<String>> sseEventStream =
                Flux.merge(preparedStream.map(sseSupport::textEvent), sseSupport.heartbeats(preparedStream));

        Disposable clientSubscription = sseEventStream.subscribe();

        assertEquals(1, upstreamSubscriptionCount.get(), "Text and heartbeat subscribers should share one upstream");
        assertEquals(0, upstreamCancellationCount.get(), "Active client should keep the upstream connected");

        clientSubscription.dispose();

        assertEquals(1, upstreamCancellationCount.get(), "Client cancellation should cancel the shared upstream");
    }

    @Test
    void heartbeatsDoNotOverflowWhenDownstreamStartsWithZeroDemand() {
        SseSupport sseSupport = new SseSupport(new ObjectMapper());
        Flux<ServerSentEvent<String>> heartbeatStream = sseSupport.heartbeats(Flux.never());

        StepVerifier.withVirtualTime(() -> heartbeatStream, 0)
                .thenAwait(Duration.ofSeconds((long) HEARTBEAT_INTERVAL_SECONDS * 3))
                .thenRequest(1)
                .thenAwait(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .assertNext(heartbeat -> assertTrue(!heartbeat.comment().isBlank()))
                .thenCancel()
                .verify();
    }
}
