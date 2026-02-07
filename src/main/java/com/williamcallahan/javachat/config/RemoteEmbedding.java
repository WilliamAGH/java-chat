package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Remote embedding service configuration.
 */
public class RemoteEmbedding {

    private static final String URL_DEF = "";
    private static final String MODEL_DEF = "text-embedding-qwen3-embedding-8b";
    private static final String API_KEY_DEF = "";
    private static final int DIM_DEF = 4_096;
    private static final int MIN_POSITIVE = 1;
    private static final String URL_KEY = "app.remote-embedding.server-url";
    private static final String MODEL_KEY = "app.remote-embedding.model";
    private static final String API_KEY_PROP = "app.remote-embedding.api-key";
    private static final String DIM_KEY = "app.remote-embedding.dimensions";
    private static final String NULL_TEXT_FMT = "%s must not be null.";
    private static final String POSITIVE_FMT = "%s must be greater than 0.";

    private String serverUrl = URL_DEF;
    private String model = MODEL_DEF;
    private String apiKey = API_KEY_DEF;
    private int dimensions = DIM_DEF;

    public RemoteEmbedding() {}

    /**
     * Validates remote embedding settings.
     */
    public void validateConfiguration() {
        requireNonNullText(URL_KEY, serverUrl);
        requireNonNullText(MODEL_KEY, model);
        requireNonNullText(API_KEY_PROP, apiKey);
        if (dimensions < MIN_POSITIVE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, DIM_KEY));
        }
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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(final String apiKey) {
        this.apiKey = requireNonNullText(API_KEY_PROP, apiKey);
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(final int dimensions) {
        this.dimensions = dimensions;
    }

    private static String requireNonNullText(final String propertyKey, final String text) {
        if (text == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_TEXT_FMT, propertyKey));
        }
        return text;
    }
}
