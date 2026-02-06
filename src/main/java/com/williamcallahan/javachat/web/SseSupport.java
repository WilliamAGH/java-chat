package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Shared SSE support utilities for streaming controllers.
 *
 * Provides JSON serialization, error event creation, and heartbeat generation
 * for consistent SSE behavior across ChatController and GuidedLearningController.
 *
 * @see SseConstants for event type constants
 */
@Component
public class SseSupport {
    private static final Logger log = LoggerFactory.getLogger(SseSupport.class);

    /** Fallback JSON payload when SSE error serialization fails. */
    private static final String ERROR_FALLBACK_JSON =
            "{\"message\":\"Error serialization failed\",\"details\":\"See server logs\"}";

    private final ObjectWriter jsonWriter;

    /**
     * Creates SSE support wired to the application's ObjectMapper.
     *
     * @param objectMapper JSON mapper for safe SSE serialization
     */
    public SseSupport(ObjectMapper objectMapper) {
        this.jsonWriter = objectMapper.writer();
    }

    /**
     * Configures HTTP response headers for SSE streaming through proxies.
     * Disables buffering for Nginx and other reverse proxies that might delay streaming.
     *
     * @param response the servlet response to configure
     */
    public void configureStreamingHeaders(HttpServletResponse response) {
        response.addHeader("X-Accel-Buffering", "no"); // Nginx: disable proxy buffering
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
    }

    /**
     * Prepares a raw content stream for SSE transmission with backpressure handling.
     * Filters empty chunks and applies the standard streaming configuration.
     *
     * @param source the raw content flux from the streaming service
     * @param chunkConsumer consumer called for each non-empty chunk (typically for accumulation)
     * @return a shared flux configured for SSE streaming
     */
    public Flux<String> prepareDataStream(Flux<String> source, Consumer<String> chunkConsumer) {
        return source.filter(chunk -> chunk != null && !chunk.isEmpty())
                .doOnNext(chunkConsumer)
                .onBackpressureBuffer(BACKPRESSURE_BUFFER_SIZE)
                // Two subscribers consume this stream in controllers:
                // 1) text event emission, 2) heartbeat termination signal.
                // autoConnect(2) prevents a race where one subscriber could miss the first chunks.
                .publish()
                .autoConnect(2);
    }

    /**
     * Serializes an object to JSON for SSE data payloads.
     *
     * @param payload object to serialize
     * @return JSON string representation
     * @throws IllegalStateException if serialization fails
     */
    public String jsonSerialize(Object payload) {
        try {
            return jsonWriter.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE data", e);
        }
    }

    /**
     * Creates a Flux containing a single SSE error event with safe JSON serialization.
     *
     * @param message user-facing error message
     * @param details additional diagnostic details
     * @return Flux emitting a single error event
     */
    public Flux<ServerSentEvent<String>> sseError(String message, String details) {
        String json;
        try {
            json = jsonWriter.writeValueAsString(new ErrorPayload(message, details));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE error payload", e);
            json = ERROR_FALLBACK_JSON;
        }
        return Flux.just(
                ServerSentEvent.<String>builder().event(EVENT_ERROR).data(json).build());
    }

    /**
     * Creates a Flux containing a single SSE status event with safe JSON serialization.
     * Uses EVENT_STATUS type for non-error status updates.
     *
     * @param message status message
     * @param details additional details
     * @return Flux emitting a single status event
     */
    public Flux<ServerSentEvent<String>> sseStatus(String message, String details) {
        String json;
        try {
            json = jsonWriter.writeValueAsString(new StatusPayload(message, details));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE status payload", e);
            json = ERROR_FALLBACK_JSON;
        }
        return Flux.just(
                ServerSentEvent.<String>builder().event(EVENT_STATUS).data(json).build());
    }

    /**
     * Creates a heartbeat Flux that emits SSE comments at regular intervals.
     * Heartbeats keep connections alive through proxies and load balancers.
     *
     * @param terminateOn Flux that signals when heartbeats should stop (typically the data stream)
     * @return Flux of SSE comment events for keepalive
     */
    public Flux<ServerSentEvent<String>> heartbeats(Flux<?> terminateOn) {
        return Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .takeUntilOther(terminateOn.ignoreElements())
                .map(tick -> ServerSentEvent.<String>builder()
                        .comment(COMMENT_KEEPALIVE)
                        .build());
    }

    /**
     * Wraps a text chunk in JSON format for SSE transmission.
     * JSON wrapping preserves whitespace that Spring's SSE handling might otherwise trim.
     *
     * @param chunk raw text content
     * @return ServerSentEvent with JSON-wrapped text
     */
    public ServerSentEvent<String> textEvent(String chunk) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_TEXT)
                .data(jsonSerialize(new ChunkPayload(chunk)))
                .build();
    }

    /**
     * Creates a status event from a notice message.
     *
     * @param summary brief status summary
     * @param details detailed status information
     * @return ServerSentEvent with status payload
     */
    public ServerSentEvent<String> statusEvent(String summary, String details) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_STATUS)
                .data(jsonSerialize(new StatusPayload(summary, details)))
                .build();
    }

    /**
     * Creates a citation event containing document references.
     *
     * @param citations list of citation objects to serialize
     * @return ServerSentEvent with citation array payload
     */
    public ServerSentEvent<String> citationEvent(Object citations) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_CITATION)
                .data(jsonSerialize(citations))
                .build();
    }

    /**
     * Creates a provider event indicating which LLM provider is handling the request.
     * Surfaces provider transparency to end-users per the no-silent-fallback policy.
     *
     * @param providerName the name of the LLM provider (e.g., "OpenAI", "GitHub Models")
     * @return ServerSentEvent with provider metadata payload
     */
    public ServerSentEvent<String> providerEvent(String providerName) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_PROVIDER)
                .data(jsonSerialize(new ProviderPayload(providerName)))
                .build();
    }

    /** Payload record for text chunks - preserves whitespace in JSON. */
    public record ChunkPayload(String text) {}

    /** Payload record for status messages. */
    public record StatusPayload(String message, String details) {}

    /** Payload record for error messages. */
    public record ErrorPayload(String message, String details) {}

    /** Payload record for provider metadata. */
    public record ProviderPayload(String provider) {}
}
