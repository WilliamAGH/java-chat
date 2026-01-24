package com.williamcallahan.javachat.config;

import java.util.Locale;

/**
 * Documentation source configuration.
 */
public class DocumentationConfig {

    private static final String ROOT_URL_DEF = "https://docs.oracle.com/en/java/javase/24/";
    private static final int JDK_DEF = 24;
    private static final String SNAP_DIR_DEF = "data/snapshots";
    private static final String PARSED_DIR_DEF = "data/parsed";
    private static final String INDEX_DIR_DEF = "data/index";
    private static final int MIN_POSITIVE = 1;
    private static final String ROOT_URL_KEY = "app.docs.root-url";
    private static final String JDK_KEY = "app.docs.jdk-version";
    private static final String SNAP_DIR_KEY = "app.docs.snapshot-dir";
    private static final String PARSED_DIR_KEY = "app.docs.parsed-dir";
    private static final String INDEX_DIR_KEY = "app.docs.index-dir";
    private static final String NULL_TEXT_FMT = "%s must not be null.";
    private static final String BLANK_TEXT_FMT = "%s must not be blank.";
    private static final String BLANK_ROOT_MSG = "Documentation root URL must not be blank.";
    private static final String POSITIVE_FMT = "%s must be greater than 0.";

    private String rootUrl = ROOT_URL_DEF;
    private int jdkVersion = JDK_DEF;
    private String snapshotDir = SNAP_DIR_DEF;
    private String parsedDir = PARSED_DIR_DEF;
    private String indexDir = INDEX_DIR_DEF;

    /**
     * Creates documentation configuration.
     */
    public DocumentationConfig() {
    }

    /**
     * Validates documentation settings.
     */
    public void validateConfiguration() {
        requireNonNullText(ROOT_URL_KEY, rootUrl);
        requireNonNullText(SNAP_DIR_KEY, snapshotDir);
        requireNonNullText(PARSED_DIR_KEY, parsedDir);
        requireNonNullText(INDEX_DIR_KEY, indexDir);
        if (jdkVersion < MIN_POSITIVE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, JDK_KEY));
        }
        if (rootUrl.isBlank()) {
            throw new IllegalStateException(BLANK_ROOT_MSG);
        }
        if (snapshotDir.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT, BLANK_TEXT_FMT, SNAP_DIR_KEY));
        }
        if (parsedDir.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT, BLANK_TEXT_FMT, PARSED_DIR_KEY));
        }
        if (indexDir.isBlank()) {
            throw new IllegalStateException(String.format(Locale.ROOT, BLANK_TEXT_FMT, INDEX_DIR_KEY));
        }
    }

    /**
     * Returns the documentation root URL.
     *
     * @return documentation root URL
     */
    public String getRootUrl() {
        return rootUrl;
    }

    /**
     * Sets the documentation root URL.
     *
     * @param rootUrl documentation root URL
     */
    public void setRootUrl(final String rootUrl) {
        this.rootUrl = requireNonNullText(ROOT_URL_KEY, rootUrl);
    }

    /**
     * Returns the configured JDK version.
     *
     * @return configured JDK version
     */
    public int getJdkVersion() {
        return jdkVersion;
    }

    /**
     * Sets the configured JDK version.
     *
     * @param jdkVersion configured JDK version
     */
    public void setJdkVersion(final int jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    /**
     * Returns the snapshot directory path.
     *
     * @return snapshot directory path
     */
    public String getSnapshotDir() {
        return snapshotDir;
    }

    /**
     * Sets the snapshot directory path.
     *
     * @param snapshotDir snapshot directory path
     */
    public void setSnapshotDir(final String snapshotDir) {
        this.snapshotDir = requireNonNullText(SNAP_DIR_KEY, snapshotDir);
    }

    /**
     * Returns the parsed document directory path.
     *
     * @return parsed document directory path
     */
    public String getParsedDir() {
        return parsedDir;
    }

    /**
     * Sets the parsed document directory path.
     *
     * @param parsedDir parsed document directory path
     */
    public void setParsedDir(final String parsedDir) {
        this.parsedDir = requireNonNullText(PARSED_DIR_KEY, parsedDir);
    }

    /**
     * Returns the index directory path.
     *
     * @return index directory path
     */
    public String getIndexDir() {
        return indexDir;
    }

    /**
     * Sets the index directory path.
     *
     * @param indexDir index directory path
     */
    public void setIndexDir(final String indexDir) {
        this.indexDir = requireNonNullText(INDEX_DIR_KEY, indexDir);
    }

    private static String requireNonNullText(final String propertyKey, final String text) {
        if (text == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_TEXT_FMT, propertyKey));
        }
        return text;
    }
}
