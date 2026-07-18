package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Loads and caches guided lesson metadata from the classpath to support guided learning flows.
 */
@Service
public class GuidedTOCProvider {
    private static final String OFFICIAL_SOURCE_KIND = "official";

    private final ObjectMapper objectMapper;
    private volatile List<GuidedLesson> cachedLessons = List.of();
    private volatile boolean tocLoaded = false;

    /**
     * Creates a TOC provider backed by a safely copied ObjectMapper instance.
     *
     * @param objectMapper configured ObjectMapper
     */
    public GuidedTOCProvider(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    /**
     * Returns an immutable lesson snapshot, loading the table of contents lazily on first access.
     */
    public synchronized List<GuidedLesson> getTOC() {
        if (tocLoaded) return immutableLessonSnapshots(cachedLessons);
        try {
            ClassPathResource tocResource = new ClassPathResource("guided/toc.json");
            try (InputStream tocStream = tocResource.getInputStream()) {
                List<GuidedLesson> loadedLessons = objectMapper
                        .readerFor(new TypeReference<List<GuidedLesson>>() {})
                        .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(tocStream);
                cachedLessons = immutableLessonSnapshots(projectOfficialSourceScopes(loadedLessons));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load guided TOC from classpath", exception);
        }
        tocLoaded = true;
        return immutableLessonSnapshots(cachedLessons);
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

    /**
     * Resolves each lesson's canonical source references into its retrieval-facing docSet projection.
     *
     * <p>The source manifests own source identities and documentation metadata. The TOC only relates
     * a lesson to those identities, while the public lesson shape receives the exact docSet values
     * projected from the manifests.</p>
     *
     * @param guidedLessons deserialized lesson metadata
     * @return immutable lessons with manifest-projected source scopes
     */
    static List<GuidedLesson> projectOfficialSourceScopes(List<GuidedLesson> guidedLessons) {
        if (guidedLessons == null) {
            throw new IllegalStateException("Guided TOC must contain a lesson array");
        }
        for (GuidedLesson guidedLesson : guidedLessons) {
            if (guidedLesson == null) {
                throw new IllegalStateException("Guided TOC cannot contain null lessons");
            }
            try {
                guidedLesson.requireValidSourceReferences();
                List<String> resolvedDocSets = guidedLesson.sourceReferences().stream()
                        .map(sourceReference -> resolveOfficialDocSet(guidedLesson.getSlug(), sourceReference))
                        .toList();
                guidedLesson.applyResolvedDocSet(resolvedDocSets);
                guidedLesson.requireValidSourceScope();
            } catch (IllegalStateException invalidSourceScope) {
                throw new IllegalStateException(
                        "Guided TOC lesson has invalid source scope: " + guidedLesson.getSlug(), invalidSourceScope);
            }
        }
        return List.copyOf(guidedLessons);
    }

    private static List<GuidedLesson> immutableLessonSnapshots(List<GuidedLesson> guidedLessons) {
        return guidedLessons.stream().map(GuidedLesson::new).toList();
    }

    private static String resolveOfficialDocSet(String lessonSlug, String sourceReference) {
        List<String> matchingCanonicalSourceIdentities = canonicalOfficialSourceIdentities()
                .filter(canonicalSourceIdentity -> canonicalSourceIdentity.equals(sourceReference))
                .toList();
        if (matchingCanonicalSourceIdentities.size() != 1) {
            throw new IllegalStateException(
                    "Guided TOC lesson references unknown official source: " + lessonSlug + " -> " + sourceReference);
        }
        return matchingCanonicalSourceIdentities.getFirst();
    }

    private static Stream<String> canonicalOfficialSourceIdentities() {
        return Stream.concat(
                DocsSourceRegistry.documentationSources().stream()
                        .filter(documentationSource -> OFFICIAL_SOURCE_KIND.equals(documentationSource.sourceKind()))
                        .map(DocsSourceRegistry.DocumentationSource::docSet),
                DocsSourceRegistry.javaApiDocumentationSources().stream()
                        .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath));
    }
}
