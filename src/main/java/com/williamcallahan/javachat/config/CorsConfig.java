package com.williamcallahan.javachat.config;

import java.util.List;
import java.util.Locale;

/**
 * CORS configuration for frontend access.
 */
public class CorsConfig {

    private static final String LOCAL_ORIGIN = "http://localhost:8085";
    private static final String LOOPBACK_ORIGIN = "http://127.0.0.1:8085";
    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_DELETE = "DELETE";
    private static final String METHOD_OPTIONS = "OPTIONS";
    private static final String HEADER_WILD = "*";
    private static final List<String> ORIGINS_DEF = List.of(LOCAL_ORIGIN, LOOPBACK_ORIGIN);
    private static final List<String> METHODS_DEF = List.of(
        METHOD_GET,
        METHOD_POST,
        METHOD_PUT,
        METHOD_DELETE,
        METHOD_OPTIONS
    );
    private static final List<String> HEADERS_DEF = List.of(HEADER_WILD);
    private static final boolean CREDENTIALS_DEF = true;
    private static final long MAX_AGE_DEF = 3_600L;
    private static final long MIN_AGE = 0L;
    private static final String ORIGINS_KEY = "app.cors.allowed-origins";
    private static final String METHODS_KEY = "app.cors.allowed-methods";
    private static final String HEADERS_KEY = "app.cors.allowed-headers";
    private static final String MAX_AGE_KEY = "app.cors.max-age-seconds";
    private static final String NULL_LIST_FMT = "%s must not be null.";
    private static final String NON_NEG_FMT = "%s must be 0 or greater.";

    private List<String> allowedOrigins = ORIGINS_DEF;
    private List<String> allowedMethods = METHODS_DEF;
    private List<String> allowedHeaders = HEADERS_DEF;
    private boolean allowCredentials = CREDENTIALS_DEF;
    private long maxAgeSeconds = MAX_AGE_DEF;

    /**
     * Creates CORS configuration.
     */
    public CorsConfig() {
    }

    /**
     * Validates CORS settings.
     */
    public void validateConfiguration() {
        requireNonNullList(ORIGINS_KEY, allowedOrigins);
        requireNonNullList(METHODS_KEY, allowedMethods);
        requireNonNullList(HEADERS_KEY, allowedHeaders);
        if (maxAgeSeconds < MIN_AGE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NON_NEG_FMT, MAX_AGE_KEY));
        }
    }

    /**
     * Returns allowed origins.
     *
     * @return allowed origins
     */
    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    /**
     * Sets allowed origins.
     *
     * @param allowedOrigins allowed origins
     */
    public void setAllowedOrigins(final List<String> allowedOrigins) {
        this.allowedOrigins = requireNonNullList(ORIGINS_KEY, allowedOrigins);
    }

    /**
     * Returns allowed HTTP methods.
     *
     * @return allowed HTTP methods
     */
    public List<String> getAllowedMethods() {
        return List.copyOf(allowedMethods);
    }

    /**
     * Sets allowed HTTP methods.
     *
     * @param allowedMethods allowed HTTP methods
     */
    public void setAllowedMethods(final List<String> allowedMethods) {
        this.allowedMethods = requireNonNullList(METHODS_KEY, allowedMethods);
    }

    /**
     * Returns allowed headers.
     *
     * @return allowed headers
     */
    public List<String> getAllowedHeaders() {
        return List.copyOf(allowedHeaders);
    }

    /**
     * Sets allowed headers.
     *
     * @param allowedHeaders allowed headers
     */
    public void setAllowedHeaders(final List<String> allowedHeaders) {
        this.allowedHeaders = requireNonNullList(HEADERS_KEY, allowedHeaders);
    }

    /**
     * Returns whether credentials are allowed.
     *
     * @return whether credentials are allowed
     */
    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    /**
     * Sets whether credentials are allowed.
     *
     * @param allowCredentials whether credentials are allowed
     */
    public void setAllowCredentials(final boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    /**
     * Returns the CORS max-age in seconds.
     *
     * @return CORS max-age in seconds
     */
    public long getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Sets the CORS max-age in seconds.
     *
     * @param maxAgeSeconds CORS max-age in seconds
     */
    public void setMaxAgeSeconds(final long maxAgeSeconds) {
        this.maxAgeSeconds = maxAgeSeconds;
    }

    private static List<String> requireNonNullList(final String propertyKey, final List<String> entries) {
        if (entries == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_LIST_FMT, propertyKey));
        }
        return List.copyOf(entries);
    }
}
