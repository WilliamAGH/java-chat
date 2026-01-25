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

    /** SSE comment content for keepalive heartbeats. */
    public static final String COMMENT_KEEPALIVE = "keepalive";

    /** Heartbeat interval in seconds to keep SSE connections alive through proxies. */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 20;

    /** Buffer capacity for backpressure handling in streaming responses. */
    public static final int BACKPRESSURE_BUFFER_SIZE = 512;

    /** Temperature for chat responses (balances creativity with accuracy). */
    public static final double DEFAULT_TEMPERATURE = 0.7;

    private SseConstants() {
        // Non-instantiable utility class
    }
}
