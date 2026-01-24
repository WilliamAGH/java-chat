package com.williamcallahan.javachat.web;

/**
 * Response for lesson content endpoint.
 *
 * @param markdown The lesson markdown content
 * @param cached Whether the content was served from cache
 */
public record LessonContentResponse(
    String markdown,
    boolean cached
) {}
