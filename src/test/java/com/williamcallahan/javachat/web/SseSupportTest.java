package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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

        assertEquals(upstreamChunks, firstSubscriberChunks, "First subscriber should receive every stream chunk");
        assertEquals(upstreamChunks, secondSubscriberChunks, "Second subscriber should receive every stream chunk");
        assertEquals(upstreamChunks, consumedChunks, "Chunk consumer should observe every chunk exactly once");
    }
}
