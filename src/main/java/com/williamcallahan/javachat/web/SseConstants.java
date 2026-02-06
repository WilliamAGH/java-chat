package com.williamcallahan.javachat.web;

/**
 * Canonical SSE event types and streaming configuration constants.
 *
 * Centralizes Server-Sent Event naming conventions and streaming parameters
 * to ensure consistency between ChatController and GuidedLearningController.
 *
 * @see ChatController
 * @see GuidedLearningController
 */
public final class SseConstants {

    /** SSE event type for error notifications sent to the client. */
    public static final String EVENT_ERROR = "error";

    /** SSE event type for diagnostic status events (retrieval progress, etc.). */
    public static final String EVENT_STATUS = "status";

    /** SSE event type for primary text chunks during streaming. */
    public static final String EVENT_TEXT = "text";

    /** SSE event type for inline citations derived from RAG documents. */
    public static final String EVENT_CITATION = "citation";

    /** SSE event type for provider metadata (which LLM provider is responding). */
    public static final String EVENT_PROVIDER = "provider";

    /** SSE comment content for keepalive heartbeats. */
    public static final String COMMENT_KEEPALIVE = "keepalive";

    /** Heartbeat interval in seconds to keep SSE connections alive through proxies. */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 20;

    /** Max number of raw model chunks to coalesce into one SSE text event. */
    public static final int STREAM_CHUNK_COALESCE_MAX_ITEMS = 24;

    /** Time window in milliseconds for coalescing raw model chunks. */
    public static final int STREAM_CHUNK_COALESCE_WINDOW_MS = 35;

    /** Maximum number of coalesced chunks buffered for downstream SSE consumers. */
    public static final int STREAM_BACKPRESSURE_BUFFER_CAPACITY = 256;

    /** Emits one warning log for every N dropped chunks/heartbeats under backpressure. */
    public static final int STREAM_BACKPRESSURE_DROP_LOG_INTERVAL = 25;

    /** Temperature for chat responses (balances creativity with accuracy). */
    public static final double DEFAULT_TEMPERATURE = 0.7;

    private SseConstants() {
        // Non-instantiable utility class
    }
}
