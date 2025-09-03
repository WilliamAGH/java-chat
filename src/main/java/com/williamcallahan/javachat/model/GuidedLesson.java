package com.williamcallahan.javachat.model;

import java.util.List;

public class GuidedLesson {
    private String slug;
    private String title;
    private String summary;
    private List<String> keywords;

    public GuidedLesson() {}

    public GuidedLesson(String slug, String title, String summary, List<String> keywords) {
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.keywords = keywords;
    }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }
}

