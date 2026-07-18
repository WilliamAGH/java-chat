package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Non-secret remote embedding service configuration.
 */
public class RemoteEmbedding {

    private static final int MIN_POSITIVE = 1;
    private static final String URL_KEY = "app.remote-embedding.server-url";
    private static final String MODEL_KEY = "app.remote-embedding.model";
    private static final String DIM_KEY = "app.remote-embedding.dimensions";
    private static final String NULL_TEXT_FMT = "%s must not be null.";
    private static final String NON_BLANK_TEXT_FMT = "%s must not be blank.";
    private static final String POSITIVE_FMT = "%s must be greater than 0.";

    private String serverUrl = "";
    private String model = "";
    private int dimensions;

    /**
     * Creates an unset configuration for Spring property binding.
     */
    public RemoteEmbedding() {}

    /**
     * Validates remote embedding settings.
     */
    public void validateConfiguration() {
        requireNonNullText(URL_KEY, serverUrl);
        requireNonBlankText(MODEL_KEY, model);
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
        this.model = requireNonBlankText(MODEL_KEY, model);
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

    private static String requireNonBlankText(final String propertyKey, final String text) {
        String configuredText = requireNonNullText(propertyKey, text);
        if (configuredText.isBlank()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NON_BLANK_TEXT_FMT, propertyKey));
        }
        return configuredText;
    }
}
