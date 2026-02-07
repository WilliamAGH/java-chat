package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Local embedding service configuration.
 */
public class LocalEmbedding {

    private static final String URL_DEF = "http://127.0.0.1:8088";
    private static final String MODEL_DEF = "text-embedding-qwen3-embedding-8b";
    private static final int DIM_DEF = 4_096;
    private static final int BATCH_SIZE_DEF = 32;
    private static final int MIN_POSITIVE = 1;
    private static final String URL_KEY = "app.local-embedding.server-url";
    private static final String MODEL_KEY = "app.local-embedding.model";
    private static final String DIM_KEY = "app.local-embedding.dimensions";
    private static final String BATCH_SIZE_KEY = "app.local-embedding.batch-size";
    private static final String NULL_TEXT_FMT = "%s must not be null.";
    private static final String BLANK_URL_MSG = "Local embedding URL must not be blank when enabled.";
    private static final String POSITIVE_FMT = "%s must be greater than 0.";

    private boolean enabled;
    private String serverUrl = URL_DEF;
    private String model = MODEL_DEF;
    private int dimensions = DIM_DEF;
    private int batchSize = BATCH_SIZE_DEF;

    public LocalEmbedding() {}

    /**
     * Validates local embedding settings.
     */
    public void validateConfiguration() {
        requireNonNullText(URL_KEY, serverUrl);
        requireNonNullText(MODEL_KEY, model);
        if (dimensions < MIN_POSITIVE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, DIM_KEY));
        }
        if (batchSize < MIN_POSITIVE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, BATCH_SIZE_KEY));
        }
        if (enabled && serverUrl.isBlank()) {
            throw new IllegalStateException(BLANK_URL_MSG);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(final String serverUrl) {
        this.serverUrl = requireNonNullText(URL_KEY, serverUrl);
    }

    public String getModel() {
        return model;
    }

    public void setModel(final String model) {
        this.model = requireNonNullText(MODEL_KEY, model);
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(final int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(final int batchSize) {
        this.batchSize = batchSize;
    }

    private static String requireNonNullText(final String propertyKey, final String text) {
        if (text == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_TEXT_FMT, propertyKey));
        }
        return text;
    }
}
