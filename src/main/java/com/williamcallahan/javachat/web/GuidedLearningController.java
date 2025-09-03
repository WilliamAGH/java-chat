package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import com.williamcallahan.javachat.service.MarkdownService;

import java.util.*;
import java.time.Duration;

@RestController
@RequestMapping("/api/guided")
public class GuidedLearningController extends BaseController {


    private final GuidedLearningService guidedService;
    private final ChatMemoryService chatMemory;

    private final MarkdownService markdownService;

    public GuidedLearningController(GuidedLearningService guidedService,
                                    ChatMemoryService chatMemory,
                                    ExceptionResponseBuilder exceptionBuilder,
                                    MarkdownService markdownService) {
        super(exceptionBuilder);
        this.guidedService = guidedService;
        this.chatMemory = chatMemory;
        this.markdownService = markdownService;
    }

    @GetMapping("/toc")
    public List<GuidedLesson> toc() {
        return guidedService.getTOC();
    }

    @GetMapping("/lesson")
    public GuidedLesson lesson(@RequestParam("slug") String slug) {
        return guidedService.getLesson(slug).orElseThrow(() -> new NoSuchElementException("Unknown lesson slug: " + slug));
    }

    @GetMapping("/citations")
    public List<Citation> citations(@RequestParam("slug") String slug) {
        return guidedService.citationsForLesson(slug);
    }

    private Enrichment sanitizeEnrichment(Enrichment e) {
        if (e == null) return null;
        e.setHints(trimFilter(e.getHints()));
        e.setReminders(trimFilter(e.getReminders()));
        e.setBackground(trimFilter(e.getBackground()));
        return e;
    }
    
    private java.util.List<String> trimFilter(java.util.List<String> in) {
        if (in == null) return java.util.List.of();
        return in.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> s.length() > 0)
                .toList();
    }

    @GetMapping("/enrich")
    public Enrichment enrich(@RequestParam("slug") String slug) {
        return sanitizeEnrichment(guidedService.enrichmentForLesson(slug));
    }

    /**
     * Stream the core lesson content (markdown) for a lesson slug.
     */
    @GetMapping(value = "/content/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamLesson(@RequestParam("slug") String slug) {
        // If cached, emit immediately as a single-frame stream
        var cached = guidedService.getCachedLessonMarkdown(slug);
        if (cached.isPresent()) {
            return Flux.just(cached.get());
        }
        return guidedService.streamLessonContent(slug);
    }

    /**
     * Non-streaming lesson content (markdown). Returns JSON with { markdown, cached }.
     */
    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> content(@RequestParam("slug") String slug) {
        var cached = guidedService.getCachedLessonMarkdown(slug);
        if (cached.isPresent()) {
            return Map.of("markdown", cached.get(), "cached", true);
        }
        // Generate synchronously (best-effort) and cache
        String md = String.join("", guidedService.streamLessonContent(slug).collectList().block(Duration.ofSeconds(25)));
        guidedService.putLessonCache(slug, md);
        return Map.of("markdown", md, "cached", false);
    }

    /**
     * Non-streaming lesson content as HTML (server-rendered markdown).
     */
    @GetMapping(value = "/content/html", produces = MediaType.TEXT_HTML_VALUE)
    public String contentHtml(@RequestParam("slug") String slug) {
        var cached = guidedService.getCachedLessonMarkdown(slug);
        String md = cached.orElseGet(() -> {
            String text = String.join("", guidedService.streamLessonContent(slug).collectList().block(Duration.ofSeconds(25)));
            guidedService.putLessonCache(slug, text);
            return text;
        });
        return markdownService.render(md);
    }

    /**
     * Stream guided answer with SSE. Body: { sessionId, slug, latest }
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", "guided:default"));
        String latest = String.valueOf(body.getOrDefault("latest", ""));
        String slug = String.valueOf(body.getOrDefault("slug", ""));

        chatMemory.addUser(sessionId, latest);
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        StringBuilder sb = new StringBuilder();

        return guidedService.streamGuidedAnswer(history, slug, latest)
                .doOnNext(sb::append)
                .doOnComplete(() -> chatMemory.addAssistant(sessionId, sb.toString()));
    }
}
