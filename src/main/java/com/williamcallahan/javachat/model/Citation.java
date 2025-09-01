package com.williamcallahan.javachat.model;

public class Citation {
    private String url;
    private String title;
    private String anchor;
    private String snippet;

    public Citation() {}
    public Citation(String url, String title, String anchor, String snippet) {
        this.url = url; this.title = title; this.anchor = anchor; this.snippet = snippet;
    }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}





