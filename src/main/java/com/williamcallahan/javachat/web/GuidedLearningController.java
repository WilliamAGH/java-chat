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

    /**
     * Retrieves the table of contents for guided learning.
     *
     * @return A {@link List} of {@link GuidedLesson} objects representing the TOC.
     */
    @GetMapping("/toc")
    public List<GuidedLesson> toc() {
        return guidedService.getTOC();
    }

    /**
     * Retrieves metadata for a specific guided learning lesson.
     *
     * @param slug The unique identifier for the lesson.
     * @return The {@link GuidedLesson} object.
     * @throws NoSuchElementException if the slug is not found.
     */
    @GetMapping("/lesson")
    public GuidedLesson lesson(@RequestParam("slug") String slug) {
        return guidedService.getLesson(slug).orElseThrow(() -> new NoSuchElementException("Unknown lesson slug: " + slug));
    }

    /**
     * Retrieves a list of citations relevant to a specific lesson.
     *
     * @param slug The unique identifier for the lesson.
     * @return A {@link List} of {@link Citation} objects.
     */
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

    /**
     * Retrieves enrichment content (hints, reminders, background) for a specific lesson.
     *
     * @param slug The unique identifier for the lesson.
     * @return An {@link Enrichment} object containing the lesson's enrichment data.
     */
    @GetMapping("/enrich")
    public Enrichment enrich(@RequestParam("slug") String slug) {
        return sanitizeEnrichment(guidedService.enrichmentForLesson(slug));
    }

    /**
     * Streams the core markdown content for a lesson slug using Server-Sent Events (SSE).
     * This endpoint is designed for dynamically loading lesson text into the UI.
     *
     * @param slug The unique identifier for the lesson.
     * @return A {@link Flux} of strings sending the markdown content as SSE data events.
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
     * Retrieves the full lesson content as a single JSON object.
     * This is a non-streaming alternative to {@link #streamLesson(String)}.
     *
     * @param slug The unique identifier for the lesson.
     * @return A {@link Map} containing the markdown content and a boolean indicating if it was from cache.
     *         <pre>{@code {"markdown": "...", "cached": true|false}}</pre>
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
     * Retrieves the lesson content rendered as HTML.
     * The markdown content is fetched (or generated) and then rendered by the server.
     *
     * @param slug The unique identifier for the lesson.
     * @return A string containing the rendered HTML.
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
     * Streams a response to a user's chat message within the context of a guided lesson.
     *
     * @param body A JSON object containing the user's request. Expected format:
     *             <pre>{@code
     *               {
     *                 "sessionId": "guided:some-session-id", // Session ID, prefixed for guided mode
     *                 "slug": "lesson-slug",               // The slug of the current lesson
     *                 "latest": "The user's question?"     // The user's message
     *               }
     *             }</pre>
     * @return A {@link Flux} of strings representing the streaming response, sent as SSE data events.
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
