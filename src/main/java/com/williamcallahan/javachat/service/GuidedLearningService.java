package com.williamcallahan.javachat.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.time.Duration;
import java.time.Instant;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

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
    private final LocalStoreService localStore;

    // Public server path of the Think Java book (as mapped by DocsSourceRegistry)
    private static final String THINK_JAVA_PDF_PATH = "/pdfs/Think Java - 2nd Edition Book.pdf";

    /** System guidance for Think Java-grounded responses with learning aid markers. */
    private static final String THINK_JAVA_GUIDANCE =
        "You are a Java learning assistant guiding the user through 'Think Java — 2nd Edition'. " +
        "Use ONLY content grounded in this book for factual claims. " +
        "Cite sources with [n] markers. Embed learning aids using {{hint:...}}, {{reminder:...}}, {{background:...}}, {{example:...}}, {{warning:...}}. " +
        "Prefer short, correct explanations with clear code examples when appropriate. If unsure, state the limitation.";

    private final String jdkVersion;

    /**
     * Creates the guided learning orchestrator using retrieval and enrichment services plus the configured JDK version hint.
     */
    public GuidedLearningService(GuidedTOCProvider tocProvider,
                                 RetrievalService retrievalService,
                                 EnrichmentService enrichmentService,
                                 ChatService chatService,
                                 LocalStoreService localStore,
                                 @Value("${app.docs.jdk-version}") String jdkVersion) {
        this.tocProvider = tocProvider;
        this.retrievalService = retrievalService;
        this.enrichmentService = enrichmentService;
        this.chatService = chatService;
        this.localStore = localStore;
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
        return enhancePdfCitationsWithPage(filtered, base);
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

        return chatService.streamAnswerWithContext(history, userMessage, filtered, THINK_JAVA_GUIDANCE);
    }
    
    /**
     * Build a complete prompt for OpenAI streaming service for guided learning.
     * This reuses the same logic as streamGuidedAnswer but returns the prompt instead of streaming.
     */
    public String buildGuidedPromptWithContext(List<Message> history, String slug, String userMessage) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String query = lesson != null ? buildLessonQuery(lesson) + "\n" + userMessage : userMessage;
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        // Build the complete prompt using ChatService's prompt building logic
        return chatService.buildPromptWithContextAndGuidance(history, userMessage, filtered, THINK_JAVA_GUIDANCE);
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
            "Use inline [n] citations that correspond to the provided context order.",
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

    // ===== PDF Pagination heuristics for /pdfs/Think Java - 2nd Edition Book.pdf =====
    private volatile Integer cachedPdfPages = null;

    private int getThinkJavaPdfPages() {
        if (cachedPdfPages != null) return cachedPdfPages;
        synchronized (this) {
            if (cachedPdfPages != null) return cachedPdfPages;
            try {
                ClassPathResource pdfResource = new ClassPathResource("public/pdfs/Think Java - 2nd Edition Book.pdf");
                try (InputStream pdfStream = pdfResource.getInputStream();
                     PDDocument document = Loader.loadPDF(pdfStream.readAllBytes())) {
                    cachedPdfPages = document.getNumberOfPages();
                }
            } catch (IOException ioException) {
                logger.error("Failed to load Think Java PDF for pagination", ioException);
                throw new IllegalStateException("Unable to read Think Java PDF", ioException);
            }
            return cachedPdfPages;
        }
    }

    private int totalChunksForUrl(String url) {
        try {
            String safe = localStore.toSafeName(url);
            Path dir = localStore.getParsedDir();
            if (dir == null) {
                return 0;
            }
            try (var stream = Files.list(dir)) {
                return (int) stream
                    .filter(path -> {
                        Path fileNamePath = path.getFileName();
                        if (fileNamePath == null) {
                            return false;
                        }
                        String fileName = fileNamePath.toString();
                        return fileName.startsWith(safe + "_") && fileName.endsWith(".txt");
                    })
                    .count();
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Unable to count local chunks for URL", ioException);
        }
    }

    private List<Citation> enhancePdfCitationsWithPage(List<Document> docs, List<Citation> citations) {
        if (docs.size() != citations.size()) return citations;
        int pages = getThinkJavaPdfPages();
        for (int docIndex = 0; docIndex < docs.size(); docIndex++) {
            Document document = docs.get(docIndex);
            Citation citation = citations.get(docIndex);
            String url = citation.getUrl();
            if (url == null || !url.toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
            Object chunkIndexMetadata = document.getMetadata().get("chunkIndex");
            int chunkIndex = -1;
            try {
                if (chunkIndexMetadata != null) {
                    chunkIndex = Integer.parseInt(String.valueOf(chunkIndexMetadata));
                }
            } catch (NumberFormatException chunkIndexParseException) {
                logger.debug("Failed to parse chunkIndex from metadata: {}",
                    sanitizeForLogText(String.valueOf(chunkIndexMetadata)));
            }
            int totalChunks = totalChunksForUrl(url);
            if (pages > 0 && chunkIndex >= 0 && totalChunks > 0) {
                int page = Math.max(1, Math.min(pages, (int) Math.round(((chunkIndex + 1.0) / totalChunks) * pages)));
                String withAnchor = url.contains("#page=") ? url : url + "#page=" + page;
                citation.setUrl(withAnchor);
                citation.setAnchor("page=" + page);
            }
        }
        return citations;
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

    private Enrichment emptyEnrichment() {
        Enrichment fallbackEnrichment = new Enrichment();
        fallbackEnrichment.setJdkVersion(jdkVersion);
        fallbackEnrichment.setHints(List.of());
        fallbackEnrichment.setReminders(List.of());
        fallbackEnrichment.setBackground(List.of());
        return fallbackEnrichment;
    }

    private static String sanitizeForLogText(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.replace("\r", "\\r").replace("\n", "\\n");
    }
}
