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
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.time.Duration;
import java.time.Instant;

import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

@Service
public class GuidedLearningService {
    private static final Logger log = LoggerFactory.getLogger(GuidedLearningService.class);

    private final GuidedTOCProvider tocProvider;
    private final RetrievalService retrievalService;
    private final EnrichmentService enrichmentService;
    private final ChatService chatService;
    private final LocalStoreService localStore;

    // Public server path of the Think Java book (as mapped by DocsSourceRegistry)
    private static final String THINK_JAVA_PDF_PATH = "/pdfs/Think Java - 2nd Edition Book.pdf";

    private final String jdkVersion;

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

    public List<GuidedLesson> getTOC() { return tocProvider.getTOC(); }

    public Optional<GuidedLesson> getLesson(String slug) { return tocProvider.findBySlug(slug); }

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

    public Enrichment enrichmentForLesson(String slug) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        if (lesson == null) return emptyEnrichment();
        String query = buildLessonQuery(lesson);
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);
        List<String> snippets = filtered.stream().map(Document::getText).limit(6).collect(Collectors.toList());
        return enrichmentService.enrich(query, jdkVersion, snippets);
    }

    public Flux<String> streamGuidedAnswer(List<Message> history, String slug, String userMessage) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String query = lesson != null ? buildLessonQuery(lesson) + "\n" + userMessage : userMessage;
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        String guidance = "You are a Java learning assistant guiding the user through 'Think Java — 2nd Edition'. " +
                "Use ONLY content grounded in this book for factual claims. " +
                "Cite sources with [n] markers. Embed learning aids using {{hint:...}}, {{reminder:...}}, {{background:...}}, {{example:...}}, {{warning:...}}. " +
                "Prefer short, correct explanations with clear code examples when appropriate. If unsure, state the limitation.";

        return chatService.streamAnswerWithContext(history, userMessage, filtered, guidance);
    }

    /**
     * Stream well-structured lesson content for the given slug.
     * Produces markdown with headings, paragraphs, lists, and an example code block.
     */
    public Flux<String> streamLessonContent(String slug) {
        var lesson = tocProvider.findBySlug(slug).orElse(null);
        String title = lesson != null ? lesson.getTitle() : slug;
        String query = lesson != null ? buildLessonQuery(lesson) : slug;

        // Retrieve Think Java-only context
        List<Document> docs = retrievalService.retrieve(query);
        List<Document> filtered = filterToBook(docs);

        // Guidance: produce a clean, layered markdown lesson body
        String guidance = String.join(" ",
            "Create a concise, beautifully formatted Java lesson using markdown only.",
            "Start with an H2 heading of the topic title.",
            "Then 1-2 short paragraphs that define and motivate the topic.",
            "Add a bullet list of 3-5 key points or rules.",
            "Include one short Java example in a fenced ```java code block with comments.",
            "Add a small numbered list (1-3 steps) when it helps understanding.",
            "Use inline [n] citations that correspond to the provided context order.",
            "Integrate enrichment markers sparingly when valuable ({{hint:...}}, {{reminder:...}}, {{background:...}}).",
            "Do NOT include a conclusion section; keep it compact and practical.",
            "If context is insufficient, state what is missing briefly."
        );

        // We pass a synthetic latestUserMessage that instructs the model to write the lesson
        String latestUserMessage = "Write the lesson for: " + title + "\nFocus on: " + query;
        List<Message> emptyHistory = List.of();
        String cacheKey = slug == null ? "" : slug;
        StringBuilder sb = new StringBuilder();
        return chatService.streamAnswerWithContext(emptyHistory, latestUserMessage, filtered, guidance)
                .doOnNext(sb::append)
                .doOnComplete(() -> putLessonCache(cacheKey, sb.toString()));
    }

    // ===== In-memory cache for lesson markdown =====
    private static final long CACHE_TTL_SECONDS = 30 * 60; // 30 minutes
    private static class CacheEntry { final String md; final Instant at; CacheEntry(String md){ this.md=md; this.at=Instant.now(); } }
    private final ConcurrentMap<String, CacheEntry> lessonCache = new ConcurrentHashMap<>();

    public Optional<String> getCachedLessonMarkdown(String slug) {
        CacheEntry e = lessonCache.getOrDefault(slug, null);
        if (e == null) return Optional.empty();
        if (Duration.between(e.at, Instant.now()).getSeconds() > CACHE_TTL_SECONDS) {
            lessonCache.remove(slug);
            return Optional.empty();
        }
        return Optional.ofNullable(e.md);
    }

    public void putLessonCache(String slug, String md) {
        if (slug != null && md != null && !md.isBlank()) {
            lessonCache.put(slug, new CacheEntry(md));
        }
    }

    // ===== PDF Pagination heuristics for /pdfs/Think Java - 2nd Edition Book.pdf =====
    private volatile Integer cachedPdfPages = null;

    private int getThinkJavaPdfPages() {
        if (cachedPdfPages != null) return cachedPdfPages;
        synchronized (this) {
            if (cachedPdfPages != null) return cachedPdfPages;
            try {
                ClassPathResource res = new ClassPathResource("public/pdfs/Think Java - 2nd Edition Book.pdf");
                try (InputStream in = res.getInputStream(); PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                    cachedPdfPages = doc.getNumberOfPages();
                }
            } catch (Exception e) {
                cachedPdfPages = 0; // unknown
            }
            return cachedPdfPages;
        }
    }

    private int totalChunksForUrl(String url) {
        try {
            String safe = localStore.toSafeName(url);
            Path dir = localStore.getParsedDir();
            int count = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, safe + "_*.txt")) {
                for (Path ignored : stream) count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Citation> enhancePdfCitationsWithPage(List<Document> docs, List<Citation> citations) {
        if (docs.size() != citations.size()) return citations;
        int pages = getThinkJavaPdfPages();
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            Citation c = citations.get(i);
            String url = c.getUrl();
            if (url == null || !url.toLowerCase().endsWith(".pdf")) continue;
            Object idxObj = d.getMetadata().get("chunkIndex");
            int chunkIdx = -1;
            try { if (idxObj != null) chunkIdx = Integer.parseInt(String.valueOf(idxObj)); } catch (NumberFormatException ignored) {}
            int totalChunks = totalChunksForUrl(url);
            if (pages > 0 && chunkIdx >= 0 && totalChunks > 0) {
                int page = Math.max(1, Math.min(pages, (int) Math.round(((chunkIdx + 1.0) / totalChunks) * pages)));
                String withAnchor = url.contains("#page=") ? url : url + "#page=" + page;
                c.setUrl(withAnchor);
                c.setAnchor("page=" + page);
            }
        }
        return citations;
    }

    private List<Document> filterToBook(List<Document> docs) {
        if (docs == null) return List.of();
        List<Document> filtered = new ArrayList<>();
        for (Document d : docs) {
            String url = String.valueOf(d.getMetadata().getOrDefault("url", ""));
            if (url != null && url.contains(THINK_JAVA_PDF_PATH)) {
                filtered.add(d);
            }
        }
        return filtered;
    }

    private String buildLessonQuery(GuidedLesson lesson) {
        StringBuilder sb = new StringBuilder();
        if (lesson.getTitle() != null) sb.append(lesson.getTitle()).append(". ");
        if (lesson.getSummary() != null) sb.append(lesson.getSummary()).append(" ");
        if (lesson.getKeywords() != null && !lesson.getKeywords().isEmpty()) {
            sb.append(String.join(", ", lesson.getKeywords()));
        }
        return sb.toString().trim();
    }

    private Enrichment emptyEnrichment() {
        Enrichment e = new Enrichment();
        e.setJdkVersion(jdkVersion);
        e.setHints(List.of());
        e.setReminders(List.of());
        e.setBackground(List.of());
        return e;
    }
}
