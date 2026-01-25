package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.support.PdfCitationEnhancer;

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

    /**
     * Base guidance for Think Java-grounded responses with learning aid markers.
     *
     * <p>This template includes a placeholder for the current lesson context, which is
     * filled in at runtime to keep responses focused on the active topic.</p>
     */
    private static final String THINK_JAVA_GUIDANCE_TEMPLATE =
        "You are a Java learning assistant guiding the user through 'Think Java — 2nd Edition'. " +
        "Use ONLY content grounded in this book for factual claims. " +
        "Do NOT include footnote references like [1] or a citations section; the UI shows sources separately. " +
        "Embed learning aids using {{hint:...}}, {{reminder:...}}, {{background:...}}, {{example:...}}, {{warning:...}}. " +
        "Prefer short, correct explanations with clear code examples when appropriate. If unsure, state the limitation.\n\n" +
        "## Current Lesson Context\n" +
        "%s\n\n" +
        "## Topic Handling Rules\n" +
        "1. Keep all responses focused on the current lesson topic.\n" +
        "2. If the user sends a greeting (hi, hello, hey, etc.) or off-topic message, " +
        "acknowledge it briefly and redirect to the lesson topic with a helpful prompt.\n" +
        "3. For off-topic Java questions, acknowledge the question and gently steer back to the current lesson, " +
        "explaining how the lesson topic relates or suggesting they complete this lesson first.\n" +
        "4. Never ignore the lesson context - every response should reinforce learning the current topic.";

    private final String jdkVersion;

    /**
     * Creates the guided learning orchestrator using retrieval and enrichment services plus the configured JDK version hint.
     */
    public GuidedLearningService(GuidedTOCProvider tocProvider,
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
    public List<GuidedLesson> getTOC() { return tocProvider.getTOC(); }

    /**
     * Returns guided lesson metadata for a slug when present.
     */
    public Optional<GuidedLesson> getLesson(String slug) { return tocProvider.findBySlug(slug); }

    /**
     * Retrieves citations for a lesson using book-focused retrieval and best-effort PDF page anchoring.
     */
    public List<Citation> citationsForLesson(String slug) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        if (lesson == null) return List.of();
        String query = buildLessonQuery(lesson);
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);
        if (filtered.isEmpty()) return List.of();
        List<Citation> base = retrievalService.toCitations(filtered);
        return pdfCitationEnhancer.enhanceWithPageAnchors(filtered, base);
    }

    /**
     * Builds enrichment markers for a lesson using retrieved snippets and the configured JDK version.
     */
    public Enrichment enrichmentForLesson(String slug) {
        logger.debug("GuidedLearningService.enrichmentForLesson called");
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        if (lesson == null) return emptyEnrichment();
        String query = buildLessonQuery(lesson);
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);
        List<String> snippets = filtered.stream().map(Document::getText).limit(6).collect(Collectors.toList());
        Enrichment enrichment = enrichmentService.enrich(query, jdkVersion, snippets);
        logger.debug("GuidedLearningService returning enrichment with hints: {}, reminders: {}, background: {}",
	            enrichment.getHints().size(), enrichment.getReminders().size(), enrichment.getBackground().size());
        return enrichment;
    }

    /**
     * Streams a guided answer grounded in the Think Java book with additional structured guidance.
     */
    public Flux<String> streamGuidedAnswer(List<Message> history, String slug, String userMessage) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String query = lesson != null ? buildLessonQuery(lesson) + "\n" + userMessage : userMessage;
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        String guidance = buildLessonGuidance(lesson);
        return chatService.streamAnswerWithContext(history, userMessage, filtered, guidance);
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
            List<Message> history,
            String slug,
            String userMessage) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String query = lesson != null ? buildLessonQuery(lesson) + "\n" + userMessage : userMessage;
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        String guidance = buildLessonGuidance(lesson);
        StructuredPrompt structuredPrompt = chatService.buildStructuredPromptWithContextAndGuidance(
                history, userMessage, filtered, guidance);
        return new GuidedChatPromptOutcome(structuredPrompt, filtered);
    }

    /**
     * Builds UI-ready citations from Think Java context documents, with best-effort PDF page anchors.
     *
     * <p>This method is designed for guided chat streaming: citation generation must never break the
     * response stream. If citation conversion or page anchor enrichment fails, it returns a best-effort
     * result and logs diagnostics.</p>
     *
     * @param bookContextDocuments retrieved Think Java documents used to ground the response
     * @return list of citations for display in the UI citation panel
     */
    public List<Citation> citationsForBookDocuments(List<Document> bookContextDocuments) {
        if (bookContextDocuments == null || bookContextDocuments.isEmpty()) {
            return List.of();
        }
        List<Citation> baseCitations;
        try {
            baseCitations = retrievalService.toCitations(bookContextDocuments);
        } catch (RuntimeException conversionFailure) {
            logger.warn("Unable to convert guided context documents into citations: {}",
                    conversionFailure.getMessage());
            return List.of();
        }

        try {
            return pdfCitationEnhancer.enhanceWithPageAnchors(bookContextDocuments, baseCitations);
        } catch (RuntimeException anchorFailure) {
            logger.warn("Unable to enhance guided citations with PDF page anchors: {}",
                    anchorFailure.getMessage());
            return baseCitations;
        }
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
        // CRITICAL FIX: For the intro lesson, the AI consistently fails to format the
        // code block correctly. We will provide a well-formatted, hardcoded response
        // for this specific lesson to ensure a reliable user experience.
        if ("introduction-to-java".equals(slug)) {
            String hardcodedContent = """
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
            return Flux.just(hardcodedContent);
        }

        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String title = lesson != null ? lesson.getTitle() : slug;
        String query = lesson != null ? buildLessonQuery(lesson) : slug;

        // Retrieve Think Java-only context
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        // Guidance: produce a clean, layered markdown lesson body
        String guidance = String.join(" ",
            "Create a concise, beautifully formatted Java lesson using markdown only.",
            "Do NOT include any heading at the top; the UI provides the title.",
            "Then 1-2 short paragraphs that define and motivate the topic.",
            "Add a bullet list of 3-5 key points or rules.",
            "Include one short Java example in a fenced ```java code block with comments.",
            "Add a small numbered list (1-3 steps) when it helps understanding.",
            "Do NOT include footnote references like [1] or a citations section; the UI shows sources separately.",
            "Do NOT include enrichment markers like {{hint:...}}; they are handled separately.",
            "Do NOT include a conclusion section; keep it compact and practical.",
            "If context is insufficient, state what is missing briefly."
        );

        // We pass a synthetic latestUserMessage that instructs the model to write the lesson
        String latestUserMessage = "Write the lesson for: " + title + "\nFocus on: " + query;
        List<Message> emptyHistory = List.of();
        StringBuilder lessonMarkdownBuilder = new StringBuilder();
        return chatService.streamAnswerWithContext(emptyHistory, latestUserMessage, filtered, guidance)
                .doOnNext(lessonMarkdownBuilder::append)
                .doOnComplete(() -> putLessonCache(slug, lessonMarkdownBuilder.toString()));
    }

    // ===== In-memory cache for lesson markdown =====
    private static final long LESSON_MARKDOWN_CACHE_TTL_MINUTES = 30;
    private static final Duration LESSON_MARKDOWN_CACHE_TTL =
        Duration.ofMinutes(LESSON_MARKDOWN_CACHE_TTL_MINUTES);

    /**
     * Stores lesson markdown alongside the time it was cached to enforce an in-memory TTL.
     */
    private record LessonMarkdownCacheEntry(String markdown, Instant cachedAt) {}

    private final ConcurrentMap<String, LessonMarkdownCacheEntry> lessonMarkdownCache =
        new ConcurrentHashMap<>();

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

    private List<Document> filterToBook(List<Document> docs) {
        List<Document> filtered = new ArrayList<>();
        for (Document document : docs) {
            String url = String.valueOf(document.getMetadata().getOrDefault("url", ""));
            if (url.contains(THINK_JAVA_PDF_PATH)) {
                filtered.add(document);
            }
        }
        return filtered;
    }

    private String buildLessonQuery(GuidedLesson lesson) {
        StringBuilder queryBuilder = new StringBuilder();
        if (lesson.getTitle() != null) queryBuilder.append(lesson.getTitle()).append(". ");
        if (lesson.getSummary() != null) queryBuilder.append(lesson.getSummary()).append(" ");
        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            queryBuilder.append(String.join(", ", lesson.getKeywords()));
        }
        return queryBuilder.toString().trim();
    }

    /**
     * Builds complete guidance for a guided learning chat, combining lesson context with system prompts.
     *
     * <p>When a lesson is provided, the guidance includes the lesson title, summary, and keywords
     * to keep responses focused on the current topic. It also integrates the guided learning
     * mode instructions from SystemPromptConfig.</p>
     *
     * @param lesson current lesson or null if no lesson context
     * @return complete guidance string for the LLM
     */
    private String buildLessonGuidance(GuidedLesson lesson) {
        String lessonContext = buildLessonContextDescription(lesson);
        String thinkJavaGuidance = String.format(THINK_JAVA_GUIDANCE_TEMPLATE, lessonContext);

        // Combine with guided learning mode instructions from SystemPromptConfig
        String guidedLearningPrompt = systemPromptConfig.getGuidedLearningPrompt();
        return systemPromptConfig.buildFullPrompt(thinkJavaGuidance, guidedLearningPrompt);
    }

    /**
     * Builds a human-readable description of the current lesson context for the LLM.
     *
     * @param lesson current lesson or null
     * @return description of the lesson context
     */
    private String buildLessonContextDescription(GuidedLesson lesson) {
        if (lesson == null) {
            return "No specific lesson selected. Provide general Java learning assistance.";
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("The user is currently studying the lesson: **")
                .append(lesson.getTitle())
                .append("**");

        if (lesson.getSummary() != null && !lesson.getSummary().isBlank()) {
            contextBuilder.append("\n\nLesson Summary: ").append(lesson.getSummary());
        }

        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            contextBuilder.append("\n\nKey concepts to cover: ")
                    .append(String.join(", ", lesson.getKeywords()));
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
