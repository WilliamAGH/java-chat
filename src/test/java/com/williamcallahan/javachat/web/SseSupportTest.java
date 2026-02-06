package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.HEARTBEAT_INTERVAL_SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Verifies SSE stream preparation semantics used by streaming chat controllers.
 */
class SseSupportTest {

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
