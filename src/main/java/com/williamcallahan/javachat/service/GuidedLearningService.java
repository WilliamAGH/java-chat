package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.support.PdfCitationEnhancer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
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
    private final PdfCitationEnhancer pdfCitationEnhancer;

    // Public server path of the Think Java book (as mapped by DocsSourceRegistry)
    private static final String THINK_JAVA_PDF_PATH = "/pdfs/Think Java - 2nd Edition Book.pdf";

    /** Maximum retrieved book snippets to include in enrichment requests. */
    private static final int MAX_ENRICHMENT_SNIPPETS = 6;

    /**
     * Pre-authored introduction lesson content, used instead of LLM generation because
     * the model consistently mis-formats the code block for this specific lesson.
     */
    private static final String INTRODUCTION_LESSON_CONTENT = """
Java is a versatile, high-level programming language that is widely used for building applications across different platforms. Understanding Java begins with grasping what a program is and how it operates. A program is essentially a set of instructions written in a specific programming language to perform a task. In Java, these instructions are encapsulated in source code files, which must be compiled into bytecode before they can be executed by the Java Virtual Machine (JVM).

Here are some key points about Java programs:
*   **Source Code**: Java programs are written in plain text files with a `.java` extension.
*   **Compilation**: The Java Compiler (javac) translates source code into bytecode, stored in `.class` files.
*   **Execution**: The Java Virtual Machine (JVM) executes the bytecode, allowing the program to run on any machine with a JVM installed.

Here's a simple example of a "Hello, World!" program in Java:

```java
// HelloWorld.java
public class HelloWorld {
    public static void main(String[] args) {
        // Print a greeting to the console
        System.out.println("Hello, World!"); // Output: Hello, World!
    }
}
```

To run this program, follow these steps:
1.  Write the code in a file named `HelloWorld.java`.
2.  Open a terminal and compile the program using `javac HelloWorld.java`.
3.  Execute the compiled bytecode with `java HelloWorld`.
""";

    /** Metadata key for the document source URL in Qdrant payload. */
    private static final String METADATA_KEY_URL = "url";

    /** Classpath directory containing curated lesson markdown files. */
    private static final String CURATED_LESSONS_RESOURCE_DIR = "guided/lessons/";

    /**
     * Base guidance for Think Java-grounded responses with learning aid markers.
     *
     * <p>This template includes a placeholder for the current lesson context, which is
     * filled in at runtime to keep responses focused on the active topic.</p>
     */
    private static final String THINK_JAVA_GUIDANCE_TEMPLATE =
            "You are a Java learning assistant guiding the user through 'Think Java — 2nd Edition'. "
                    + "Use ONLY content grounded in this book for factual claims. "
                    + "If the book does not cover a topic, say so plainly and ask before stepping outside the book. "
                    + "Do NOT include footnote references like [1] or a citations section; the UI shows sources separately. "
                    + "Embed learning aids using {{hint:...}}, {{reminder:...}}, {{background:...}}, {{example:...}}, {{warning:...}}. "
                    + "Prefer short, correct explanations with clear code examples when appropriate. If unsure, state the limitation.%n%n"
                    + "## Current Lesson Context%n"
                    + "%s%n%n"
                    + "## Topic Handling Rules%n"
                    + "1. Keep all responses focused on the current lesson topic.%n"
                    + "2. If the user sends a greeting (hi, hello, hey, etc.) or off-topic message, "
                    + "acknowledge it briefly and redirect to the lesson topic with a helpful prompt.%n"
                    + "3. For off-topic Java questions, acknowledge the question and gently steer back to the current lesson, "
                    + "explaining how the lesson topic relates or suggesting they complete this lesson first.%n"
                    + "4. Never ignore the lesson context - every response should reinforce learning the current topic.";

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
            PdfCitationEnhancer pdfCitationEnhancer,
            @Value("${app.docs.jdk-version}") String jdkVersion) {
        this.tocProvider = tocProvider;
        this.retrievalService = retrievalService;
        this.enrichmentService = enrichmentService;
        this.chatService = chatService;
        this.systemPromptConfig = systemPromptConfig;
        this.pdfCitationEnhancer = pdfCitationEnhancer;
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

    /**
     * Retrieves citations for a lesson using book-focused retrieval and best-effort PDF page anchoring.
     */
    public List<Citation> citationsForLesson(String slug) {
        return tocProvider
                .findBySlug(slug)
                .map(lesson -> {
                    String query = buildLessonQuery(lesson);
                    List<Document> retrievedDocuments = retrievalService.retrieve(query);
                    List<Document> bookDocuments = filterToBook(retrievedDocuments);
                    if (bookDocuments.isEmpty()) return List.<Citation>of();
                    List<Citation> baseCitations =
                            retrievalService.toCitations(bookDocuments).citations();
                    return pdfCitationEnhancer.enhanceWithPageAnchors(bookDocuments, baseCitations);
                })
                .orElse(List.of());
    }

    /**
     * Builds enrichment markers for a lesson using retrieved snippets and the configured JDK version.
     */
    public Enrichment enrichmentForLesson(String slug) {
        logger.debug("GuidedLearningService.enrichmentForLesson called");
        return tocProvider
                .findBySlug(slug)
                .map(lesson -> {
                    String query = buildLessonQuery(lesson);
                    List<Document> retrievedDocuments = retrievalService.retrieve(query);
                    List<Document> bookDocuments = filterToBook(retrievedDocuments);
                    List<String> snippets = bookDocuments.stream()
                            .map(Document::getText)
                            .limit(MAX_ENRICHMENT_SNIPPETS)
                            .collect(Collectors.toList());
                    Enrichment enrichment = enrichmentService.enrich(query, jdkVersion, snippets);
                    logger.debug(
                            "GuidedLearningService returning enrichment with hints: {}, reminders: {}, background: {}",
                            enrichment.getHints().size(),
                            enrichment.getReminders().size(),
                            enrichment.getBackground().size());
                    return enrichment;
                })
                .orElseGet(this::emptyEnrichment);
    }

    /**
     * Streams a guided answer grounded in the Think Java book with additional structured guidance.
     */
    public Flux<String> streamGuidedAnswer(List<Message> history, String slug, String userMessage) {
        Optional<GuidedLesson> lessonOptional = tocProvider.findBySlug(slug);
        String query = lessonOptional
                .map(lesson -> buildLessonQuery(lesson) + "\n" + userMessage)
                .orElse(userMessage);
        List<Document> retrievedDocuments = retrievalService.retrieve(query);
        List<Document> bookDocuments = filterToBook(retrievedDocuments);

        String guidance = lessonOptional.map(this::buildLessonGuidance).orElseGet(this::buildDefaultGuidance);
        return chatService.streamAnswerWithContext(history, userMessage, bookDocuments, guidance);
    }

    /**
     * Builds a structured prompt for guided learning with intelligent truncation support.
     *
     * <p>Retrieves Think Java book context and builds a structured prompt that can be
     * truncated segment-by-segment rather than character-by-character.</p>
     *
     * @param history conversation history
     * @param slug lesson slug for context retrieval
     * @param userMessage user's question
     * @return guided prompt outcome including structured prompt and context documents
     */
    public GuidedChatPromptOutcome buildStructuredGuidedPromptWithContext(
            List<Message> history, String slug, String userMessage) {
        Optional<GuidedLesson> lessonOptional = tocProvider.findBySlug(slug);
        String query = lessonOptional
                .map(lesson -> buildLessonQuery(lesson) + "\n" + userMessage)
                .orElse(userMessage);
        List<Document> retrievedDocuments = retrievalService.retrieve(query);
        List<Document> bookDocuments = filterToBook(retrievedDocuments);

        String guidance = lessonOptional.map(this::buildLessonGuidance).orElseGet(this::buildDefaultGuidance);
        StructuredPrompt structuredPrompt =
                chatService.buildStructuredPromptWithContextAndGuidance(history, userMessage, bookDocuments, guidance);
        return new GuidedChatPromptOutcome(structuredPrompt, bookDocuments);
    }

    /**
     * Builds UI-ready citations from Think Java context documents with PDF page anchors.
     *
     * <p>Converts documents to citations and enhances them with page-anchor fragments.
     * Propagates all failures to the caller; UI resilience decisions belong in the controller layer.</p>
     *
     * @param bookContextDocuments retrieved Think Java documents used to ground the response
     * @return citations enhanced with PDF page anchors
     */
    public List<Citation> citationsForBookDocuments(List<Document> bookContextDocuments) {
        if (bookContextDocuments == null || bookContextDocuments.isEmpty()) {
            return List.of();
        }
        RetrievalService.CitationOutcome citationOutcome = retrievalService.toCitations(bookContextDocuments);
        if (citationOutcome.failedConversionCount() > 0) {
            logger.warn(
                    "Guided citation conversion had {} failure(s) out of {} documents",
                    citationOutcome.failedConversionCount(),
                    bookContextDocuments.size());
        }
        List<Citation> baseCitations = citationOutcome.citations();
        return pdfCitationEnhancer.enhanceWithPageAnchors(bookContextDocuments, baseCitations);
    }

    /**
     * Represents the guided prompt and the Think Java context documents used for grounding.
     *
     * <p>Normalizes {@code bookContextDocuments} to an unmodifiable list: {@link List#of()} when null,
     * otherwise {@link List#copyOf(List)}.</p>
     *
     * @param structuredPrompt structured prompt for LLM streaming
     * @param bookContextDocuments Think Java-only context documents used for grounding and citations
     * @throws IllegalArgumentException when structuredPrompt is null
     */
    public record GuidedChatPromptOutcome(StructuredPrompt structuredPrompt, List<Document> bookContextDocuments) {
        public GuidedChatPromptOutcome {
            if (structuredPrompt == null) {
                throw new IllegalArgumentException("Structured prompt cannot be null");
            }
            bookContextDocuments = bookContextDocuments == null ? List.of() : List.copyOf(bookContextDocuments);
        }
    }

    /**
     * Stream well-structured lesson content for the given slug.
     * Produces markdown with headings, paragraphs, lists, and an example code block.
     */
    public Flux<String> streamLessonContent(String slug) {
        // Curated lessons override AI generation for slugs where the model produces unreliable formatting.
        Optional<String> curatedMarkdown = loadCuratedLessonMarkdown(slug);
        if (curatedMarkdown.isPresent()) {
            return Flux.just(curatedMarkdown.get());
        }

        Optional<GuidedLesson> lessonOptional = tocProvider.findBySlug(slug);
        String title = lessonOptional.map(GuidedLesson::getTitle).orElse(slug);
        String query = lessonOptional.map(this::buildLessonQuery).orElse(slug);

        // Retrieve Think Java-only context
        List<Document> retrievedDocuments = retrievalService.retrieve(query);
        List<Document> bookDocuments = filterToBook(retrievedDocuments);

        // Guidance: produce a clean, layered markdown lesson body
        String guidance = String.join(
                " ",
                "Create a concise, beautifully formatted Java lesson using markdown only.",
                "Do NOT include any heading at the top; the UI provides the title.",
                "Then 1-2 short paragraphs that define and motivate the topic.",
                "Add a bullet list of 3-5 key points or rules.",
                "Include one short Java example in a fenced ```java code block with comments.",
                "Add a small numbered list (1-3 steps) when it helps understanding.",
                "Do NOT include footnote references like [1] or a citations section; the UI shows sources separately.",
                "Do NOT include enrichment markers like {{hint:...}}; they are handled separately.",
                "Do NOT include a conclusion section; keep it compact and practical.",
                "If context is insufficient, state what is missing briefly.");

        // We pass a synthetic latestUserMessage that instructs the model to write the lesson
        String latestUserMessage = "Write the lesson for: " + title + "\nFocus on: " + query;
        List<Message> emptyHistory = List.of();
        StringBuilder lessonMarkdownBuilder = new StringBuilder();
        return chatService
                .streamAnswerWithContext(emptyHistory, latestUserMessage, bookDocuments, guidance)
                .doOnNext(lessonMarkdownBuilder::append)
                .doOnComplete(() -> putLessonCache(slug, lessonMarkdownBuilder.toString()));
    }

    // ===== In-memory cache for lesson markdown =====
    private static final Duration LESSON_MARKDOWN_CACHE_TTL = Duration.ofMinutes(30);

    /**
     * Stores lesson markdown alongside the time it was cached to enforce an in-memory TTL.
     */
    private record LessonMarkdownCacheEntry(String markdown, Instant cachedAt) {}

    private final ConcurrentMap<String, LessonMarkdownCacheEntry> lessonMarkdownCache = new ConcurrentHashMap<>();

    /**
     * Returns cached lesson markdown when present and not expired.
     */
    public Optional<String> getCachedLessonMarkdown(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        LessonMarkdownCacheEntry cacheEntry = lessonMarkdownCache.get(slug);
        if (cacheEntry == null) return Optional.empty();
        if (Duration.between(cacheEntry.cachedAt(), Instant.now()).compareTo(LESSON_MARKDOWN_CACHE_TTL) > 0) {
            lessonMarkdownCache.remove(slug);
            return Optional.empty();
        }
        return Optional.of(cacheEntry.markdown());
    }

    /**
     * Caches lesson markdown for later reuse when the slug and content are non-blank.
     */
    public void putLessonCache(String slug, String markdown) {
        if (slug == null || slug.isBlank()) {
            return;
        }
        if (markdown == null || markdown.isBlank()) {
            return;
        }
        lessonMarkdownCache.put(slug, new LessonMarkdownCacheEntry(markdown, Instant.now()));
    }

    /**
     * Returns pre-authored lesson markdown for slugs where LLM generation is unreliable.
     */
    private Optional<String> loadCuratedLessonMarkdown(String slug) {
        if ("introduction-to-java".equals(slug)) {
            return Optional.of(INTRODUCTION_LESSON_CONTENT);
        }
        return Optional.empty();
    }

    private List<Document> filterToBook(List<Document> retrievedDocuments) {
        List<Document> bookDocuments = new ArrayList<>();
        for (Document document : retrievedDocuments) {
            String sourceUrl = String.valueOf(document.getMetadata().getOrDefault(METADATA_KEY_URL, ""));
            if (sourceUrl.contains(THINK_JAVA_PDF_PATH)) {
                bookDocuments.add(document);
            }
        }
        return bookDocuments;
    }

    private String buildLessonQuery(GuidedLesson lesson) {
        StringBuilder queryBuilder = new StringBuilder();
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
    private String buildLessonGuidance(GuidedLesson lesson) {
        String lessonContext = buildLessonContextDescription(lesson);
        return buildGuidanceFromContext(lessonContext);
    }

    /**
     * Builds default guidance when no specific lesson is selected.
     *
     * @return general Java learning guidance string for the LLM
     */
    private String buildDefaultGuidance() {
        String defaultLessonContext = "No specific lesson selected. Provide general Java learning assistance.";
        return buildGuidanceFromContext(defaultLessonContext);
    }

    /**
     * Combines a lesson context description with the Think Java guidance template and system prompts.
     *
     * @param lessonContext human-readable lesson context to embed in the template
     * @return complete guidance string for the LLM
     */
    private String buildGuidanceFromContext(String lessonContext) {
        String thinkJavaGuidance = String.format(THINK_JAVA_GUIDANCE_TEMPLATE, lessonContext);
        String guidedLearningPrompt = systemPromptConfig.getGuidedLearningPrompt();
        return systemPromptConfig.buildFullPrompt(thinkJavaGuidance, guidedLearningPrompt);
    }

    /**
     * Builds a human-readable description of the current lesson context for the LLM.
     *
     * @param lesson current lesson (never null)
     * @return description of the lesson context
     */
    private String buildLessonContextDescription(GuidedLesson lesson) {
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder
                .append("The user is currently studying the lesson: **")
                .append(lesson.getTitle())
                .append("**");

        if (lesson.getSummary() != null && !lesson.getSummary().isBlank()) {
            contextBuilder.append("\n\nLesson Summary: ").append(lesson.getSummary());
        }

        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            contextBuilder.append("\n\nKey concepts to cover: ").append(String.join(", ", lesson.getKeywords()));
        }

        return contextBuilder.toString();
    }

    private Enrichment emptyEnrichment() {
        Enrichment fallbackEnrichment = new Enrichment();
        fallbackEnrichment.setJdkVersion(jdkVersion);
        fallbackEnrichment.setHints(List.of());
        fallbackEnrichment.setReminders(List.of());
        fallbackEnrichment.setBackground(List.of());
        return fallbackEnrichment;
    }
}
