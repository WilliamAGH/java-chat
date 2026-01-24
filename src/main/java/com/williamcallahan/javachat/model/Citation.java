package com.williamcallahan.javachat.model;

import java.util.Objects;

/**
 * Represents a citation with a source URL and display metadata.
 */
public class Citation {
    private static final String DEFAULT_URL = "";
    private static final String DEFAULT_TITLE = "";
    private static final String DEFAULT_ANCHOR = "";
    private static final String DEFAULT_SNIPPET = "";

    private String url;
    private String title;
    private String anchor;
    private String snippet;

    /**
     * Creates an empty citation.
     */
    public Citation() {
        this(DEFAULT_URL, DEFAULT_TITLE, DEFAULT_ANCHOR, DEFAULT_SNIPPET);
    }

    /**
     * Creates a citation with full details.
     *
     * @param url source URL
     * @param title display title
     * @param anchor optional anchor within the source
     * @param snippet excerpt used for display
     */
    public Citation(String url, String title, String anchor, String snippet) {
        this.url = Objects.requireNonNullElse(url, DEFAULT_URL);
        this.title = Objects.requireNonNullElse(title, DEFAULT_TITLE);
        this.anchor = Objects.requireNonNullElse(anchor, DEFAULT_ANCHOR);
        this.snippet = Objects.requireNonNullElse(snippet, DEFAULT_SNIPPET);
    }

    /**
     * Returns the source URL.
     *
     * @return source URL
     */
    public String getUrl() {
        return Objects.requireNonNullElse(url, DEFAULT_URL);
    }

    /**
     * Updates the source URL.
     *
     * @param url source URL
     */
    public void setUrl(String url) {
        this.url = Objects.requireNonNullElse(url, DEFAULT_URL);
    }

    /**
     * Returns the display title.
     *
     * @return citation title
     */
    public String getTitle() {
        return Objects.requireNonNullElse(title, DEFAULT_TITLE);
    }

    /**
     * Updates the display title.
     *
     * @param title citation title
     */
    public void setTitle(String title) {
        this.title = Objects.requireNonNullElse(title, DEFAULT_TITLE);
    }

    /**
     * Returns the source anchor.
     *
     * @return anchor fragment, if present
     */
    public String getAnchor() {
        return Objects.requireNonNullElse(anchor, DEFAULT_ANCHOR);
    }

    /**
     * Updates the source anchor.
     *
     * @param anchor anchor fragment
     */
    public void setAnchor(String anchor) {
        this.anchor = Objects.requireNonNullElse(anchor, DEFAULT_ANCHOR);
    }

    /**
     * Returns the display snippet.
     *
     * @return snippet text
     */
    public String getSnippet() {
        return Objects.requireNonNullElse(snippet, DEFAULT_SNIPPET);
    }

    /**
     * Updates the display snippet.
     *
     * @param snippet snippet text
     */
    public void setSnippet(String snippet) {
        this.snippet = Objects.requireNonNullElse(snippet, DEFAULT_SNIPPET);
    }
}




