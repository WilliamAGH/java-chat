package com.williamcallahan.javachat.model;

/**
 * Represents a citation with a source URL and display metadata.
 */
public class Citation {
    private String url;
    private String title;
    private String anchor;
    private String snippet;

    /**
     * Creates an empty citation.
     */
    public Citation() {}

    /**
     * Creates a citation with full details.
     *
     * @param url source URL
     * @param title display title
     * @param anchor optional anchor within the source
     * @param snippet excerpt used for display
     */
    public Citation(String url, String title, String anchor, String snippet) {
        this.url = url;
        this.title = title;
        this.anchor = anchor;
        this.snippet = snippet;
    }

    /**
     * Returns the source URL.
     *
     * @return source URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Updates the source URL.
     *
     * @param url source URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the display title.
     *
     * @return citation title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Updates the display title.
     *
     * @param title citation title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the source anchor.
     *
     * @return anchor fragment, if present
     */
    public String getAnchor() {
        return anchor;
    }

    /**
     * Updates the source anchor.
     *
     * @param anchor anchor fragment
     */
    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    /**
     * Returns the display snippet.
     *
     * @return snippet text
     */
    public String getSnippet() {
        return snippet;
    }

    /**
     * Updates the display snippet.
     *
     * @param snippet snippet text
     */
    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}




