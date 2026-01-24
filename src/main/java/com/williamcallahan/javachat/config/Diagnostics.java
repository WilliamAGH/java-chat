package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Diagnostics configuration for streaming logs.
 */
public class Diagnostics {

    private static final int SAMPLE_MIN = 0;
    private static final String SAMPLE_KEY = "app.diagnostics.stream-chunk-sample";
    private static final String NON_NEG_FMT = "%s must be 0 or greater.";

    private boolean streamChunkLog;
    private int streamChunkSample;

    /**
     * Creates diagnostics configuration.
     */
    public Diagnostics() {
    }

    /**
     * Validates diagnostics settings.
     */
    public void validateConfiguration() {
        if (streamChunkSample < SAMPLE_MIN) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NON_NEG_FMT, SAMPLE_KEY));
        }
    }

    /**
     * Returns whether stream chunks are logged.
     *
     * @return whether stream chunks are logged
     */
    public boolean isStreamChunkLogging() {
        return streamChunkLog;
    }

    /**
     * Sets whether stream chunks are logged.
     *
     * @param streamChunkLog whether stream chunks are logged
     */
    public void setStreamChunkLogging(final boolean streamChunkLog) {
        this.streamChunkLog = streamChunkLog;
    }

    /**
     * Returns the stream chunk logging sample interval.
     *
     * @return stream chunk logging sample interval
     */
    public int getStreamChunkSample() {
        return streamChunkSample;
    }

    /**
     * Sets the stream chunk logging sample interval.
     *
     * @param streamChunkSample stream chunk logging sample interval
     */
    public void setStreamChunkSample(final int streamChunkSample) {
        this.streamChunkSample = streamChunkSample;
    }
}
