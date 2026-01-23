package com.williamcallahan.javachat.model;

import java.util.List;

/**
 * Guided lesson metadata used for structured learning flows.
 */
public class GuidedLesson {
    private String slug;
    private String title;
    private String summary;
    private List<String> keywords = List.of();

    /**
     * Creates an empty guided lesson container.
     */
    public GuidedLesson() {
    }

    /**
     * Creates a guided lesson with the supplied metadata.
     *
     * @param slug lesson slug identifier
     * @param title lesson title
     * @param summary lesson summary
     * @param keywords lesson keyword list
     */
    public GuidedLesson(String slug, String title, String summary, List<String> keywords) {
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * Returns the lesson slug identifier.
     *
     * @return lesson slug
     */
    public String getSlug() {
        return slug;
    }

    /**
     * Sets the lesson slug identifier.
     *
     * @param slug lesson slug
     */
    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * Returns the lesson title.
     *
     * @return lesson title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the lesson title.
     *
     * @param title lesson title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the lesson summary.
     *
     * @return lesson summary
     */
    public String getSummary() {
        return summary;
    }

    /**
     * Sets the lesson summary.
     *
     * @param summary lesson summary
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * Returns the lesson keywords.
     *
     * @return keyword list
     */
    public List<String> getKeywords() {
        return List.copyOf(keywords);
    }

    /**
     * Sets the lesson keywords.
     *
     * @param keywords keyword list
     */
    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
