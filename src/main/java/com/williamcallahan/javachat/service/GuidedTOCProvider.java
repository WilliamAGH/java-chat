package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Loads and caches guided lesson metadata from the classpath to support guided learning flows.
 */
@Service
public class GuidedTOCProvider {
    private final ObjectMapper mapper;
    private volatile List<GuidedLesson> cache = Collections.emptyList();
    private volatile boolean tocLoaded = false;

    /**
     * Creates a TOC provider backed by a safely copied ObjectMapper instance.
     *
     * @param mapper configured ObjectMapper
     */
    public GuidedTOCProvider(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    }

    /**
     * Returns the lesson table of contents, loading it lazily from the classpath on first access.
     */
    public synchronized List<GuidedLesson> getTOC() {
        if (tocLoaded) return cache;
        try {
            ClassPathResource tocResource = new ClassPathResource("guided/toc.json");
            try (InputStream tocStream = tocResource.getInputStream()) {
                List<GuidedLesson> loadedLessons = mapper.readerFor(new TypeReference<List<GuidedLesson>>() {})
                        .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(tocStream);
                cache = List.copyOf(loadedLessons);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load guided TOC from classpath", exception);
        }
        tocLoaded = true;
        return cache;
    }

    /**
     * Finds a lesson by its slug in the cached table of contents.
     */
    public Optional<GuidedLesson> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        String normalizedSlug = AsciiTextNormalizer.toLowerAscii(slug);
        return getTOC().stream()
                .filter(lesson -> {
                    String lessonSlug = lesson.getSlug();
                    return lessonSlug != null && normalizedSlug.equals(AsciiTextNormalizer.toLowerAscii(lessonSlug));
                })
                .findFirst();
    }
}
