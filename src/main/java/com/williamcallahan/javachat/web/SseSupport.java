package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
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

    private static final Counter DROPPED_COALESCED_CHUNK_COUNTER =
            Metrics.counter("javachat.sse.backpressure.dropped_chunks");
    private static final Counter DROPPED_HEARTBEAT_COUNTER =
            Metrics.counter("javachat.sse.backpressure.dropped_heartbeats");

    private final ObjectWriter jsonWriter;
    private final AtomicLong droppedCoalescedChunkCount = new AtomicLong();
    private final AtomicLong droppedHeartbeatCount = new AtomicLong();

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
                .bufferTimeout(STREAM_CHUNK_COALESCE_MAX_ITEMS, Duration.ofMillis(STREAM_CHUNK_COALESCE_WINDOW_MS))
                .filter(chunkBatch -> !chunkBatch.isEmpty())
                .map(chunkBatch -> String.join("", chunkBatch))
                .doOnNext(chunk -> chunkConsumer.accept(chunk))
                // Keep buffering bounded and drop oldest coalesced chunks under sustained
                // downstream pressure to avoid unbounded memory growth.
                .onBackpressureBuffer(
                        STREAM_BACKPRESSURE_BUFFER_CAPACITY,
                        this::recordDroppedCoalescedChunk,
                        BufferOverflowStrategy.DROP_OLDEST)
                // Two subscribers consume this stream in controllers:
                // 1) text event emission, 2) heartbeat termination signal.
                // autoConnect(2) prevents a race where one subscriber could miss the first chunks.
                .publish()
                .autoConnect(2);
    }

    /**
     * Serializes an object to JSON for SSE data payloads.
     *
     * @param objectToSerialize object to serialize
     * @return JSON string representation
     * @throws IllegalStateException if serialization fails
     */
    public String jsonSerialize(Object objectToSerialize) {
        try {
            return jsonWriter.writeValueAsString(objectToSerialize);
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
        return sseError(SseEventPayload.builder(message).details(details).build());
    }

    /**
     * Creates a Flux containing a single SSE error event with typed diagnostic metadata.
     * Uses a localized fallback when error serialization itself fails, since this is the
     * terminal error path with nowhere further to propagate.
     */
    public Flux<ServerSentEvent<String>> sseError(SseEventPayload payload) {
        String json;
        try {
            json = jsonWriter.writeValueAsString(payload);
        } catch (JsonProcessingException serializationFailure) {
            log.error("Failed to serialize SSE error payload", serializationFailure);
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
        return sseStatus(SseEventPayload.builder(message).details(details).build());
    }

    /**
     * Creates a Flux containing a single SSE status event with typed diagnostic metadata.
     * Serialization failures propagate as exceptions — callers handle via onErrorResume.
     */
    public Flux<ServerSentEvent<String>> sseStatus(SseEventPayload payload) {
        String json = jsonSerialize(payload);
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
                .onBackpressureDrop(ignoredTick -> recordDroppedHeartbeat())
                .takeUntilOther(terminateOn.ignoreElements())
                .map(tick -> ServerSentEvent.<String>builder()
                        .comment(COMMENT_KEEPALIVE)
                        .build());
    }

    private void recordDroppedCoalescedChunk(String droppedChunk) {
        DROPPED_COALESCED_CHUNK_COUNTER.increment();
        long totalDroppedChunks = droppedCoalescedChunkCount.incrementAndGet();
        if (totalDroppedChunks % STREAM_BACKPRESSURE_DROP_LOG_INTERVAL == 0) {
            log.warn(
                    "Dropped {} coalesced SSE chunks due to downstream backpressure "
                            + "(bufferCapacity={}, droppedChunkLength={})",
                    totalDroppedChunks,
                    STREAM_BACKPRESSURE_BUFFER_CAPACITY,
                    droppedChunk.length());
        }
    }

    private void recordDroppedHeartbeat() {
        DROPPED_HEARTBEAT_COUNTER.increment();
        long totalDroppedHeartbeats = droppedHeartbeatCount.incrementAndGet();
        if (totalDroppedHeartbeats % STREAM_BACKPRESSURE_DROP_LOG_INTERVAL == 0) {
            log.warn("Dropped {} SSE heartbeats due to downstream backpressure", totalDroppedHeartbeats);
        }
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
        return statusEvent(SseEventPayload.builder(summary).details(details).build());
    }

    /**
     * Creates a status event with typed diagnostic metadata.
     */
    public ServerSentEvent<String> statusEvent(SseEventPayload payload) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_STATUS)
                .data(jsonSerialize(payload))
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

    /**
     * Maps runtime streaming notices from the LLM provider into SSE status events.
     *
     * @param notices flux of streaming notices from the provider
     * @return flux of ServerSentEvents with structured status payloads
     */
    public Flux<ServerSentEvent<String>> streamingNoticeEvents(Flux<OpenAIStreamingService.StreamingNotice> notices) {
        return notices.map(notice -> statusEvent(SseEventPayload.builder(notice.summary())
                .details(notice.diagnosticContext())
                .code(notice.code())
                .retryable(notice.retryable())
                .provider(notice.provider())
                .stage(notice.stage())
                .attempt(notice.attempt())
                .maxAttempts(notice.maxAttempts())
                .build()));
    }

    /**
     * Creates a citation-partial-failure status event flux if a warning message is present.
     *
     * @param citationWarning user-facing warning message, or null if no warning
     * @return flux with a single status event, or empty if no warning
     */
    public Flux<ServerSentEvent<String>> citationWarningStatusFlux(String citationWarning) {
        if (citationWarning == null) {
            return Flux.empty();
        }
        return Flux.just(statusEvent(SseEventPayload.builder(citationWarning)
                .details("Citations could not be loaded")
                .code(STATUS_CODE_CITATION_PARTIAL_FAILURE)
                .retryable(false)
                .stage(STATUS_STAGE_CITATION)
                .build()));
    }

    /**
     * Creates a single-event error flux for SSE stream error resume handlers.
     *
     * @param userFacingMessage user-facing error description
     * @param diagnosticDetails diagnostic details for debugging
     * @param retryable whether the client should retry
     * @return flux emitting a single error SSE event
     */
    public Flux<ServerSentEvent<String>> streamErrorEvent(
            String userFacingMessage, String diagnosticDetails, boolean retryable) {
        String statusCode =
                retryable ? STATUS_CODE_STREAM_PROVIDER_RETRYABLE_ERROR : STATUS_CODE_STREAM_PROVIDER_FATAL_ERROR;
        return sseError(SseEventPayload.builder(userFacingMessage)
                .details(diagnosticDetails)
                .code(statusCode)
                .retryable(retryable)
                .stage(STATUS_STAGE_STREAM)
                .build());
    }

    /** Payload record for text chunks - preserves whitespace in JSON. */
    public record ChunkPayload(String text) {}

    /**
     * Payload record for status and error SSE events.
     * Event-type distinction is handled by the SSE event type string, not by the payload record.
     * Use {@link #builder(String)} to construct — avoids 8-param positional calls with null padding.
     */
    public record SseEventPayload(
            String message,
            String details,
            String code,
            Boolean retryable,
            String provider,
            String stage,
            Integer attempt,
            Integer maxAttempts) {

        /** Creates a builder with the required message field. */
        public static Builder builder(String message) {
            return new Builder(message);
        }

        /** Fluent builder that lets callers set only the fields they need. */
        public static final class Builder {
            private final String message;
            private String details;
            private String code;
            private Boolean retryable;
            private String provider;
            private String stage;
            private Integer attempt;
            private Integer maxAttempts;

            private Builder(String message) {
                this.message = message;
            }

            public Builder details(String details) {
                this.details = details;
                return this;
            }

            public Builder code(String code) {
                this.code = code;
                return this;
            }

            public Builder retryable(Boolean retryable) {
                this.retryable = retryable;
                return this;
            }

            public Builder provider(String provider) {
                this.provider = provider;
                return this;
            }

            public Builder stage(String stage) {
                this.stage = stage;
                return this;
            }

            public Builder attempt(Integer attempt) {
                this.attempt = attempt;
                return this;
            }

            public Builder maxAttempts(Integer maxAttempts) {
                this.maxAttempts = maxAttempts;
                return this;
            }

            /** Builds the immutable payload record. */
            public SseEventPayload build() {
                return new SseEventPayload(message, details, code, retryable, provider, stage, attempt, maxAttempts);
            }
        }
    }

    /** Payload record for provider metadata. */
    public record ProviderPayload(String provider) {}
}
