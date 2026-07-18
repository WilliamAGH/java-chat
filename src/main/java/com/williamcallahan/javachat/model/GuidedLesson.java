package com.williamcallahan.javachat.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Guided lesson metadata and canonical official-source scope used for structured learning flows.
 */
public class GuidedLesson {
    private String slug;
    private String title;
    private String summary;
    private List<String> keywords = List.of();
    private String technology = "";
    private List<String> sourceReferences = List.of();
    private List<String> docSet = List.of();

    /**
     * Creates an empty guided lesson container.
     */
    public GuidedLesson() {}

    /**
     * Creates an independent lesson snapshot so callers cannot mutate the original metadata.
     *
     * @param lessonToCopy lesson metadata to snapshot
     */
    public GuidedLesson(GuidedLesson lessonToCopy) {
        GuidedLesson sourceLesson = Objects.requireNonNull(lessonToCopy, "lessonToCopy");
        this.slug = sourceLesson.slug;
        this.title = sourceLesson.title;
        this.summary = sourceLesson.summary;
        this.keywords = sourceLesson.getKeywords();
        this.technology = sourceLesson.technology;
        this.sourceReferences = sourceLesson.sourceReferences();
        this.docSet = sourceLesson.getDocSet();
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

    /**
     * Returns the technology whose official documentation grounds the lesson.
     *
     * @return technology name
     */
    public String getTechnology() {
        return technology;
    }

    /**
     * Sets the technology whose official documentation grounds the lesson.
     *
     * @param technology technology name
     */
    public void setTechnology(String technology) {
        this.technology = technology == null ? "" : technology;
    }

    /**
     * Returns the manifest identities associated with this lesson before source-scope projection.
     *
     * <p>This intentionally is not a JavaBean getter: source references configure the packaged
     * TOC and never form part of the public lesson API.</p>
     *
     * @return canonical manifest identities in lesson order
     */
    public List<String> sourceReferences() {
        return List.copyOf(sourceReferences);
    }

    /**
     * Sets canonical manifest identities read from the packaged TOC.
     *
     * @param sourceReferences exact source identities owned by the manifests
     */
    @JsonProperty(value = "sourceReferences", access = JsonProperty.Access.WRITE_ONLY)
    public void setSourceReferences(List<String> sourceReferences) {
        this.sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
    }

    /**
     * Returns the canonical documentation-set tokens allowed to ground the lesson.
     *
     * <p>The singular property name preserves parity with the canonical Qdrant payload key
     * {@code docSet}; the list represents an any-of constraint over that key.</p>
     *
     * @return allowed documentation-set tokens
     */
    public List<String> getDocSet() {
        return List.copyOf(docSet);
    }

    /**
     * Applies the documentation-set projection resolved from canonical source identities.
     *
     * @param resolvedDocSets canonical documentation-set tokens allowed to ground the lesson
     */
    public void applyResolvedDocSet(List<String> resolvedDocSets) {
        this.docSet = resolvedDocSets == null ? List.of() : List.copyOf(resolvedDocSets);
    }

    /**
     * Enforces the manifest-reference metadata required before source-scope projection.
     *
     * @throws IllegalStateException when source references are absent or malformed
     */
    public void requireValidSourceReferences() {
        requireValidTokens(sourceReferences, "sourceReferences");
    }

    /**
     * Enforces the source-scope metadata required before a lesson participates in retrieval.
     *
     * @throws IllegalStateException when technology or documentation-set metadata is absent or malformed
     */
    public void requireValidSourceScope() {
        if (technology.isBlank()) {
            throw new IllegalStateException("Guided lesson technology cannot be blank");
        }
        if (!technology.equals(technology.trim())) {
            throw new IllegalStateException("Guided lesson technology must be trimmed");
        }
        requireValidTokens(docSet, "docSet");
    }

    private static void requireValidTokens(List<String> sourceTokens, String tokenName) {
        if (sourceTokens.isEmpty()) {
            throw new IllegalStateException("Guided lesson " + tokenName + " cannot be empty");
        }
        Set<String> retainedTokens = new HashSet<>();
        for (String sourceToken : sourceTokens) {
            if (sourceToken.isBlank()) {
                throw new IllegalStateException("Guided lesson " + tokenName + " cannot contain blank tokens");
            }
            if (!sourceToken.equals(sourceToken.trim())) {
                throw new IllegalStateException("Guided lesson " + tokenName + " tokens must be trimmed");
            }
            if (!retainedTokens.add(sourceToken)) {
                throw new IllegalStateException("Guided lesson " + tokenName + " cannot contain duplicate tokens");
            }
        }
    }
}
