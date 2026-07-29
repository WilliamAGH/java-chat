package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.markdown.MarkdownCitation;
import com.williamcallahan.javachat.domain.prompt.PromptSegmentPriority;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Orchestrates guided learning flows over curated lesson metadata using retrieval, enrichment, and streaming chat.
 */
@Service
public class GuidedLearningService {
    private static final Logger logger = LoggerFactory.getLogger(GuidedLearningService.class);

    private final GuidedTOCProvider tocProvider;
    private final RetrievalService retrievalService;
    private final EnrichmentService enrichmentService;
    private final ChatService chatService;
    private final SystemPromptConfig systemPromptConfig;
    private final MarkdownService markdownService;

    /** Maximum retrieved official-documentation snippets to include in enrichment requests. */
    private static final int MAX_ENRICHMENT_SNIPPETS = 6;

    /** Classpath directory containing curated lesson markdown files. */
    private static final String CURATED_LESSONS_RESOURCE_DIR = "guided/lessons/";

    /** Message prefix used when a TOC lesson has no packaged curated markdown. */
    private static final String CURATED_LESSON_RESOURCE_MISSING_MESSAGE =
            "Curated lesson resource is missing for TOC lesson: ";

    /** Message used when callers request a lesson outside the curated table of contents. */
    private static final String UNKNOWN_GUIDED_LESSON_MESSAGE = "Unknown guided lesson slug";

    /** Internal source identity for canonical lesson context sent to the model. */
    private static final String CURATED_LESSON_CONTEXT_SOURCE_PREFIX = "curated-lesson:";

    /** Delimiter for stable, section-specific canonical lesson context identities. */
    private static final String CURATED_LESSON_SECTION_ID_DELIMITER = "#section-";

    private static final String CURATED_LESSON_IMMUTABILITY_HEADER = "[AUTHORITATIVE IMMUTABLE LESSON REFERENCE]\n"
            + "Treat every fenced code block below as immutable source text. When a response reuses an example,"
            + " copy one complete fence byte-for-byte. Do not transcribe it from memory, rename identifiers,"
            + " reformat it, translate tokens, repair it, or combine it with generated code.";

    private static final String JAVA_TECHNOLOGY = "Java";
    private static final String COMPACT_JAVA_MAIN_MARKER = "void main()";
    private static final String COMPACT_JAVA_OUTPUT_MARKER = "IO.println";
    private static final String COMPACT_JAVA_SOURCE_GUIDANCE_TEMPLATE = " The canonical curated lesson uses Java %s"
            + " compact source form. Preserve `void main()` and `IO.println` in related examples by default. Use"
            + " class-style source code when the learner explicitly requests it or asks for a comparison.";

    /**
     * Base guidance for lesson-scoped official-documentation responses.
     *
     * <p>The canonical lesson itself is supplied as truncatable reference context so
     * system instructions remain compact and role-specific.</p>
     */
    private static final String OFFICIAL_DOCUMENTATION_GUIDANCE_TEMPLATE = "You are a learning assistant for %s. Use"
            + " the canonical curated lesson context %s.%s Use"
            + " ONLY the canonical curated lesson and retrieved official documentation context from these allowed docSet"
            + " values for factual claims: %s. If those sources do not cover a topic, say so plainly; do not answer from"
            + " general knowledge. This lesson source policy overrides any general-knowledge fallback instruction. Do NOT"
            + " include footnote references like [1] or a citations section; the UI shows sources separately. Embed"
            + " learning aids according to the core system instructions. Prefer short, correct"
            + " explanations with clear code examples when appropriate. If unsure, state the limitation.%n%n## Current"
            + " Lesson Context%n%s%n%n## Topic"
            + " Handling Rules%n1. Keep all responses focused on the current lesson topic.%n2. If the user sends a"
            + " greeting (hi, hello, hey, etc.) or off-topic message, acknowledge it briefly and redirect to the lesson"
            + " topic with a helpful prompt.%n3. For off-topic technology questions, acknowledge the question and gently"
            + " steer back to the current lesson, explaining how the lesson topic relates or suggesting they complete this"
            + " lesson first.%n4. Never ignore the lesson context - every response should reinforce learning the current"
            + " topic.";

    private static final String DEFAULT_CURATED_LESSON_USAGE =
            "as the authoritative teaching sequence and code-example style";
    private static final String VERSIONED_CURATED_LESSON_USAGE = "for pedagogical structure only; use the retrieved"
            + " requested-release API documentation for every version-specific factual claim and code example, and do"
            + " not project newer Java syntax or APIs onto an older requested release";
    private static final String DEFAULT_CURATED_CODE_GUIDANCE = " When the canonical curated lesson already contains a"
            + " code example that answers the question, copy one complete fenced example byte-for-byte from the"
            + " authoritative curated lesson context. Do not transcribe it from memory or alter any identifier, syntax,"
            + " comment, whitespace, or token. Do not synthesize, combine, or add a second code example unless the learner"
            + " explicitly asks for an alternative or comparison.";
    private static final List<String> SUPPORTED_JAVA_API_VERSIONS =
            DocsSourceRegistry.javaApiDocumentationSources().stream()
                    .map(DocsSourceRegistry.JavaApiDocumentationSource::javaRelease)
                    .toList();

    private final String jdkVersion;

    /**
     * Creates the guided learning orchestrator using retrieval and enrichment services plus the configured JDK version hint.
     */
    public GuidedLearningService(
            GuidedTOCProvider tocProvider,
            RetrievalService retrievalService,
            EnrichmentService enrichmentService,
            ChatService chatService,
            SystemPromptConfig systemPromptConfig,
            MarkdownService markdownService,
            @Value("${app.docs.jdk-version}") String jdkVersion) {
        this.tocProvider = tocProvider;
        this.retrievalService = retrievalService;
        this.enrichmentService = enrichmentService;
        this.chatService = chatService;
        this.systemPromptConfig = systemPromptConfig;
        this.markdownService = markdownService;
        this.jdkVersion = jdkVersion;
    }

    /**
     * Returns the guided lesson table of contents.
     */
    public List<GuidedLesson> getTOC() {
        return tocProvider.getTOC();
    }

    /**
     * Returns guided lesson metadata for a slug when present.
     */
    public Optional<GuidedLesson> getLesson(String slug) {
        return tocProvider.findBySlug(slug);
    }

    /** Returns the sources authored directly into the canonical lesson markdown. */
    public List<Citation> citationsForLesson(String slug) {
        GuidedLesson lesson = requireListedLesson(slug);
        String curatedLessonMarkdown = requiredCuratedLessonMarkdown(lesson);
        return markdownService.processStructured(curatedLessonMarkdown).citations().stream()
                .map(GuidedLearningService::toLessonCitation)
                .toList();
    }

    private static Citation toLessonCitation(MarkdownCitation markdownCitation) {
        return new Citation(markdownCitation.url(), markdownCitation.title(), "", markdownCitation.snippet());
    }

    /**
     * Builds enrichment markers for a lesson using retrieved snippets and the configured JDK version.
     */
    public Enrichment enrichmentForLesson(String slug) {
        logger.debug("GuidedLearningService.enrichmentForLesson called");
        GuidedLesson lesson = requireListedLesson(slug);
        String query = buildLessonQuery(lesson);
        List<Document> lessonContextDocuments = retrieveLessonContext(lesson, query);
        List<String> snippets = lessonContextDocuments.stream()
                .map(Document::getText)
                .limit(MAX_ENRICHMENT_SNIPPETS)
                .toList();
        Enrichment enrichment = enrichmentService.enrich(query, jdkVersion, snippets);
        logger.debug(
                "GuidedLearningService returning enrichment with hints: {}, reminders: {}, background: {}",
                enrichment.getHints().size(),
                enrichment.getReminders().size(),
                enrichment.getBackground().size());
        return enrichment;
    }

    /**
     * Builds a structured prompt for guided learning with intelligent truncation support.
     *
     * <p>Retrieves lesson-scoped official documentation and builds a structured prompt that can be
     * truncated segment-by-segment rather than character-by-character.</p>
     *
     * @param history conversation history
     * @param slug lesson slug for context retrieval
     * @param userMessage user's question
     * @return guided prompt outcome including structured prompt and context documents
     */
    public GuidedChatPromptOutcome buildStructuredGuidedPromptWithContext(
            List<Message> history, String slug, String userMessage) {
        return buildStructuredGuidedPromptWithContext(history, slug, userMessage, notice -> {});
    }

    /**
     * Builds the guided prompt while reporting live retrieval progress for the lesson context
     * search.
     *
     * @param history conversation history
     * @param slug lesson slug for context retrieval
     * @param userMessage user's question
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @return guided prompt outcome including structured prompt and context documents
     */
    public GuidedChatPromptOutcome buildStructuredGuidedPromptWithContext(
            List<Message> history,
            String slug,
            String userMessage,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener) {
        return buildStructuredGuidedPromptWithContext(
                history,
                slug,
                userMessage,
                retrievalProgressListener,
                System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos());
    }

    /**
     * Builds the guided prompt within the caller-owned response-preparation deadline.
     *
     * @param history conversation history
     * @param slug lesson slug for context retrieval
     * @param userMessage user's question
     * @param retrievalProgressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return guided prompt outcome including structured prompt and context documents
     */
    public GuidedChatPromptOutcome buildStructuredGuidedPromptWithContext(
            List<Message> history,
            String slug,
            String userMessage,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener,
            long stageDeadlineNanos) {
        GuidedLesson lesson = requireListedLesson(slug);
        String curatedLessonMarkdown = requiredCuratedLessonMarkdown(lesson);
        String query = buildLessonQuery(lesson) + "\n" + userMessage;
        List<String> parsedVersions = QueryVersionExtractor.extractVersionNumbers(query, SUPPORTED_JAVA_API_VERSIONS);
        boolean isJavaLesson = JAVA_TECHNOLOGY.equals(lesson.getTechnology());
        List<String> requestedVersions = isJavaLesson ? parsedVersions : List.of();
        List<String> effectiveDocSets = effectiveDocSetsFor(lesson, requestedVersions);
        List<Document> lessonContextDocuments =
                retrieveLessonContext(query, effectiveDocSets, retrievalProgressListener, stageDeadlineNanos);

        String guidance = buildLessonGuidance(lesson, curatedLessonMarkdown, effectiveDocSets, requestedVersions);
        List<Document> curatedLessonContextDocuments = curatedLessonContextDocuments(lesson, curatedLessonMarkdown);
        List<Document> promptContextDocuments =
                new ArrayList<>(lessonContextDocuments.size() + curatedLessonContextDocuments.size());
        promptContextDocuments.addAll(curatedLessonContextDocuments);
        promptContextDocuments.addAll(lessonContextDocuments);
        StructuredPrompt structuredPrompt = chatService.buildStructuredPromptWithContextAndGuidance(
                history, userMessage, promptContextDocuments, guidance);
        return new GuidedChatPromptOutcome(
                prioritizeCuratedLessonContext(structuredPrompt, lesson), lessonContextDocuments);
    }

    private static StructuredPrompt prioritizeCuratedLessonContext(
            StructuredPrompt structuredPrompt, GuidedLesson lesson) {
        String curatedLessonSectionIdPrefix =
                CURATED_LESSON_CONTEXT_SOURCE_PREFIX + lesson.getSlug() + CURATED_LESSON_SECTION_ID_DELIMITER;
        return structuredPrompt.withContextDocuments(structuredPrompt.contextDocuments().stream()
                .map(contextDocument -> contextDocument.documentId().startsWith(curatedLessonSectionIdPrefix)
                        ? contextDocument.withPriority(PromptSegmentPriority.HIGH)
                        : contextDocument)
                .toList());
    }

    private List<Document> curatedLessonContextDocuments(GuidedLesson lesson, String curatedLessonMarkdown) {
        String lessonSourceIdentity = CURATED_LESSON_CONTEXT_SOURCE_PREFIX + lesson.getSlug();
        List<String> lessonSections = markdownService.splitIntoSections(curatedLessonMarkdown);
        List<Document> lessonContextDocuments = new ArrayList<>(lessonSections.size());
        for (int sectionIndex = 0; sectionIndex < lessonSections.size(); sectionIndex++) {
            String lessonSection = lessonSections.get(sectionIndex);
            if (sectionIndex == 0) {
                lessonSection = CURATED_LESSON_IMMUTABILITY_HEADER + "\n\n" + lessonSection;
            }
            String lessonSectionIdentity =
                    lessonSourceIdentity + CURATED_LESSON_SECTION_ID_DELIMITER + (sectionIndex + 1);
            lessonContextDocuments.add(Document.builder()
                    .id(lessonSectionIdentity)
                    .text(lessonSection)
                    .metadata(QdrantPayloadFieldSchema.URL_FIELD, lessonSectionIdentity)
                    .build());
        }
        return List.copyOf(lessonContextDocuments);
    }

    /**
     * Builds citations from the exact guided context retained by provider-specific truncation.
     *
     * <p>The outcome preserves conversion state for the SSE boundary while {@link RetrievalService}
     * remains the sole owner of retained-document selection and citation conversion.</p>
     *
     * @param promptOutcome prompt and its pre-truncation lesson context documents
     * @param retainedDocumentIds source identities retained after provider truncation
     * @return citations and conversion failures for retained lesson context only
     */
    public RetrievalService.CitationOutcome citationOutcomeForRetainedContext(
            GuidedChatPromptOutcome promptOutcome, List<String> retainedDocumentIds) {
        return retrievalService.toCitationsForRetainedContext(
                promptOutcome.lessonContextDocuments(), retainedDocumentIds);
    }

    /**
     * Represents the guided prompt and its lesson-scoped official context documents.
     *
     * <p>Normalizes {@code lessonContextDocuments} to an unmodifiable list: {@link List#of()} when null,
     * otherwise {@link List#copyOf(List)}.</p>
     *
     * @param structuredPrompt structured prompt for LLM streaming
     * @param lessonContextDocuments official context documents used for grounding and citations
     * @throws IllegalArgumentException when structuredPrompt is null
     */
    public record GuidedChatPromptOutcome(StructuredPrompt structuredPrompt, List<Document> lessonContextDocuments) {
        public GuidedChatPromptOutcome {
            if (structuredPrompt == null) {
                throw new IllegalArgumentException("Structured prompt cannot be null");
            }
            lessonContextDocuments = lessonContextDocuments == null ? List.of() : List.copyOf(lessonContextDocuments);
        }
    }

    /**
     * Streams the authoritative classpath markdown for a listed guided lesson.
     *
     * @param slug requested lesson slug
     * @return one markdown emission from the packaged curated lesson
     * @throws NoSuchElementException when the slug is absent from the guided table of contents
     * @throws CuratedLessonResourceMissingException when a listed lesson has no packaged markdown resource
     */
    public Flux<String> streamLessonContent(String slug) {
        GuidedLesson listedLesson = requireListedLesson(slug);
        String curatedMarkdown = requiredCuratedLessonMarkdown(listedLesson);
        return Flux.just(curatedMarkdown);
    }

    /**
     * Signals a broken curated-lesson package invariant instead of substituting generated content.
     */
    public static final class CuratedLessonResourceMissingException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the invariant failure for the listed lesson whose resource is absent. */
        public CuratedLessonResourceMissingException(String lessonSlug) {
            super(CURATED_LESSON_RESOURCE_MISSING_MESSAGE + lessonSlug);
        }
    }

    /**
     * Signals that a learner requested a Java API release absent from the configured documentation corpus.
     */
    public static final class UnsupportedJavaDocumentationReleaseException extends IllegalArgumentException {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates an actionable validation failure from the canonical supported-release list. */
        public UnsupportedJavaDocumentationReleaseException(String requestedRelease, List<String> supportedReleases) {
            super("Java "
                    + requestedRelease
                    + " is not supported. Supported Java documentation releases: "
                    + String.join(", ", supportedReleases)
                    + ".");
        }
    }

    /**
     * Loads curated lesson markdown from the authoritative classpath lesson package.
     *
     * <p>Curated lessons are stored as {@code .md} files under {@value #CURATED_LESSONS_RESOURCE_DIR}
     * on the classpath. Returns {@link Optional#empty()} when no resource exists for the given slug.</p>
     *
     * @param slug lesson slug used to resolve the resource filename
     * @return curated markdown content, or empty when no curated resource exists
     * @throws UncheckedIOException when the resource exists but cannot be read
     */
    private Optional<String> loadCuratedLessonMarkdown(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        if (!slug.matches("^[a-z0-9][a-z0-9-]*$") || slug.endsWith("-")) {
            return Optional.empty();
        }
        String resourcePath = CURATED_LESSONS_RESOURCE_DIR + slug + ".md";
        try (InputStream lessonStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (lessonStream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(lessonStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException readFailure) {
            throw new UncheckedIOException("Failed to read curated lesson resource: " + resourcePath, readFailure);
        }
    }

    private GuidedLesson requireListedLesson(String slug) {
        return tocProvider
                .findBySlug(slug)
                .orElseThrow(() -> new NoSuchElementException(UNKNOWN_GUIDED_LESSON_MESSAGE));
    }

    private String requiredCuratedLessonMarkdown(GuidedLesson listedLesson) {
        String canonicalLessonSlug = listedLesson.getSlug();
        return loadCuratedLessonMarkdown(canonicalLessonSlug)
                .orElseThrow(() -> new CuratedLessonResourceMissingException(canonicalLessonSlug));
    }

    private List<Document> retrieveLessonContext(GuidedLesson lesson, String query) {
        return retrieveLessonContext(query, effectiveDocSetsFor(lesson, List.of()));
    }

    private List<Document> retrieveLessonContext(String query, List<String> effectiveDocSets) {
        return retrieveLessonContext(query, effectiveDocSets, notice -> {});
    }

    private List<Document> retrieveLessonContext(
            String query,
            List<String> effectiveDocSets,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener) {
        return retrieveLessonContext(
                query,
                effectiveDocSets,
                retrievalProgressListener,
                System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos());
    }

    private List<Document> retrieveLessonContext(
            String query,
            List<String> effectiveDocSets,
            Consumer<RetrievalService.RetrievalNotice> retrievalProgressListener,
            long stageDeadlineNanos) {
        return retrievalService.retrieve(
                query,
                RetrievalConstraint.forOfficialDocSets(effectiveDocSets),
                retrievalProgressListener,
                stageDeadlineNanos);
    }

    private List<String> effectiveDocSetsFor(GuidedLesson lesson, List<String> requestedVersions) {
        lesson.requireValidSourceScope();
        if (!JAVA_TECHNOLOGY.equals(lesson.getTechnology()) || requestedVersions.isEmpty()) {
            return List.copyOf(lesson.getDocSet());
        }
        List<String> effectiveDocSets = new ArrayList<>(requestedVersions.size());
        for (String requestedVersion : requestedVersions) {
            DocsSourceRegistry.JavaApiDocumentationSource requestedSource =
                    DocsSourceRegistry.javaApiDocumentationSources().stream()
                            .filter(javaApiSource -> javaApiSource.javaRelease().equals(requestedVersion))
                            .findFirst()
                            .orElseThrow(() -> new UnsupportedJavaDocumentationReleaseException(
                                    requestedVersion, SUPPORTED_JAVA_API_VERSIONS));
            effectiveDocSets.add(requestedSource.relativeMirrorPath());
        }
        return List.copyOf(effectiveDocSets);
    }

    private String buildLessonQuery(GuidedLesson lesson) {
        StringBuilder queryBuilder = new StringBuilder();
        if (!lesson.getTechnology().isBlank()) {
            queryBuilder.append(lesson.getTechnology()).append(". ");
        }
        if (lesson.getTitle() != null) queryBuilder.append(lesson.getTitle()).append(". ");
        if (lesson.getSummary() != null)
            queryBuilder.append(lesson.getSummary()).append(" ");
        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            queryBuilder.append(String.join(", ", lesson.getKeywords()));
        }
        return queryBuilder.toString().trim();
    }

    /**
     * Builds complete guidance for a guided learning chat, combining lesson context with system prompts.
     *
     * <p>Includes the lesson title, summary, and keywords to keep responses focused on the
     * current topic. Integrates the guided learning mode instructions from SystemPromptConfig.</p>
     *
     * @param lesson current lesson (never null)
     * @return complete guidance string for the LLM
     */
    private String buildLessonGuidance(
            GuidedLesson lesson,
            String curatedLessonMarkdown,
            List<String> effectiveDocSets,
            List<String> requestedVersions) {
        String lessonContext = buildLessonContextDescription(lesson, effectiveDocSets);
        return buildGuidanceFromContext(
                lesson, curatedLessonMarkdown, lessonContext, effectiveDocSets, requestedVersions);
    }

    /**
     * Combines a lesson context description with official-source guidance and system prompts.
     *
     * <p>This template includes a placeholder for the current lesson context, which is
     * filled in at runtime to keep responses focused on the active topic.</p>
     *
     * @param lesson current lesson source scope
     * @param curatedLessonMarkdown canonical lesson markdown that owns the teaching sequence and code style
     * @param lessonContext human-readable lesson context to embed in the template
     * @return complete guidance string for the LLM
     */
    private String buildGuidanceFromContext(
            GuidedLesson lesson,
            String curatedLessonMarkdown,
            String lessonContext,
            List<String> effectiveDocSets,
            List<String> requestedVersions) {
        String officialDocumentationGuidance = String.format(
                OFFICIAL_DOCUMENTATION_GUIDANCE_TEMPLATE,
                lesson.getTechnology(),
                requestedVersions.isEmpty() ? DEFAULT_CURATED_LESSON_USAGE : VERSIONED_CURATED_LESSON_USAGE,
                compactJavaSourceGuidance(lesson, curatedLessonMarkdown, requestedVersions)
                        + (requestedVersions.isEmpty() ? DEFAULT_CURATED_CODE_GUIDANCE : ""),
                String.join(", ", effectiveDocSets),
                lessonContext);
        String guidedLearningPrompt = systemPromptConfig.getGuidedLearningPrompt();
        return systemPromptConfig.buildFullPrompt(officialDocumentationGuidance, guidedLearningPrompt);
    }

    /**
     * Preserves the compact Java source form only when the canonical lesson itself teaches it.
     *
     * @param lesson lesson that owns the curated content
     * @param curatedLessonMarkdown canonical lesson content
     * @return compact-source guidance for matching Java lessons, otherwise an empty suffix
     */
    private String compactJavaSourceGuidance(
            GuidedLesson lesson, String curatedLessonMarkdown, List<String> requestedVersions) {
        if (!JAVA_TECHNOLOGY.equals(lesson.getTechnology())
                || requestedVersions.stream().anyMatch(requestedVersion -> !jdkVersion.equals(requestedVersion))
                || !curatedLessonMarkdown.contains(COMPACT_JAVA_MAIN_MARKER)
                || !curatedLessonMarkdown.contains(COMPACT_JAVA_OUTPUT_MARKER)) {
            return "";
        }
        return String.format(COMPACT_JAVA_SOURCE_GUIDANCE_TEMPLATE, jdkVersion);
    }

    /**
     * Builds a human-readable description of the current lesson context for the LLM.
     *
     * @param lesson current lesson (never null)
     * @return description of the lesson context
     */
    private String buildLessonContextDescription(GuidedLesson lesson, List<String> effectiveDocSets) {
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder
                .append("The user is currently studying the lesson: **")
                .append(lesson.getTitle())
                .append("**")
                .append("\n\nTechnology: ")
                .append(lesson.getTechnology())
                .append("\n\nAllowed official docSet values: ")
                .append(String.join(", ", effectiveDocSets));

        if (lesson.getSummary() != null && !lesson.getSummary().isBlank()) {
            contextBuilder.append("\n\nLesson Summary: ").append(lesson.getSummary());
        }

        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            contextBuilder.append("\n\nKey concepts to cover: ").append(String.join(", ", lesson.getKeywords()));
        }

        return contextBuilder.toString();
    }
}
