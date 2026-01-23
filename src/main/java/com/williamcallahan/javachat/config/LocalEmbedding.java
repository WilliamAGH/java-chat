package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Local embedding service configuration.
 */
public class LocalEmbedding {

    private static final String URL_DEF = "http://127.0.0.1:1234";
    private static final String MODEL_DEF = "text-embedding-qwen3-embedding-8b";
    private static final int DIM_DEF = 4_096;
    private static final int MIN_POSITIVE = 1;
    private static final String URL_KEY = "app.local-embedding.server-url";
    private static final String MODEL_KEY = "app.local-embedding.model";
    private static final String DIM_KEY = "app.local-embedding.dimensions";
    private static final String NULL_TEXT_FMT = "%s must not be null.";
    private static final String BLANK_URL_MSG = "Local embedding URL must not be blank when enabled.";
    private static final String DIM_POS_MSG = "Local embedding dimensions must be greater than 0.";

    private boolean enabled;
    private String serverUrl = URL_DEF;
    private String model = MODEL_DEF;
    private int dimensions = DIM_DEF;
    private boolean hashFallback;

    /**
     * Creates local embedding configuration.
     */
    public LocalEmbedding() {
    }

    /**
     * Validates local embedding settings.
     */
    public void validateConfiguration() {
        requireNonNullText(URL_KEY, serverUrl);
        requireNonNullText(MODEL_KEY, model);
        if (dimensions < MIN_POSITIVE) {
            throw new IllegalArgumentException(DIM_POS_MSG);
        }
        if (enabled && serverUrl.isBlank()) {
            throw new IllegalStateException(BLANK_URL_MSG);
        }
    }

    /**
     * Returns whether local embedding is enabled.
     *
     * @return whether local embedding is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether local embedding is enabled.
     *
     * @param enabled whether local embedding is enabled
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the local embedding server URL.
     *
     * @return local embedding server URL
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * Sets the local embedding server URL.
     *
     * @param serverUrl local embedding server URL
     */
    public void setServerUrl(final String serverUrl) {
        this.serverUrl = requireNonNullText(URL_KEY, serverUrl);
    }

    /**
     * Returns the local embedding model name.
     *
     * @return local embedding model name
     */
    public String getModel() {
        return model;
    }

    /**
     * Sets the local embedding model name.
     *
     * @param model local embedding model name
     */
    public void setModel(final String model) {
        this.model = requireNonNullText(MODEL_KEY, model);
    }

    /**
     * Returns the embedding dimensions.
     *
     * @return embedding dimensions
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Sets the embedding dimensions.
     *
     * @param dimensions embedding dimensions
     */
    public void setDimensions(final int dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * Returns whether hash fallback is used when the local model is disabled.
     *
     * @return whether hash fallback is used when the local model is disabled
     */
    public boolean isUseHashWhenDisabled() {
        return hashFallback;
    }

    /**
     * Sets whether hash fallback is used when the local model is disabled.
     *
     * @param hashFallback whether hash fallback is used when the local model is disabled
     */
    public void setUseHashWhenDisabled(final boolean hashFallback) {
        this.hashFallback = hashFallback;
    }

    private static String requireNonNullText(final String propertyKey, final String text) {
        if (text == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_TEXT_FMT, propertyKey));
        }
        return text;
    }
}
