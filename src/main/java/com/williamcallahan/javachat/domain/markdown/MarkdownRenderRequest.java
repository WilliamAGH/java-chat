package com.williamcallahan.javachat.domain.markdown;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Accepts raw markdown input for server-side rendering.
 */
public record MarkdownRenderRequest(String content) {

    /**
     * Creates a request while normalizing null content to an empty string.
     *
     * @param content markdown input text
     * @return normalized render request
     */
    @JsonCreator
    public static MarkdownRenderRequest create(@JsonProperty("content") String content) {
        return new MarkdownRenderRequest(content == null ? "" : content);
    }

    public MarkdownRenderRequest {
        Objects.requireNonNull(content, "Markdown content cannot be null");
    }

    /**
     * Indicates whether the request contains any non-blank markdown.
     *
     * @return true when the content is blank
     */
    public boolean isBlank() {
        return content.isBlank();
    }
}
