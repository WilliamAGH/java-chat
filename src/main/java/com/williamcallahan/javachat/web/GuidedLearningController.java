package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import jakarta.annotation.security.PermitAll;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import com.williamcallahan.javachat.service.MarkdownService;

import java.util.*;
import java.time.Duration;

/**
 * Exposes guided learning endpoints for lesson metadata, citations, enrichment, and streaming lesson content.
 */
@RestController
@RequestMapping("/api/guided")
@PermitAll
@PreAuthorize("permitAll()")
public class GuidedLearningController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(GuidedLearningController.class);

    /** Timeout for synchronous lesson content generation operations. */
    private static final Duration LESSON_CONTENT_TIMEOUT = Duration.ofSeconds(25);

    private final GuidedLearningService guidedService;
    private final ChatMemoryService chatMemory;
    private final OpenAIStreamingService openAIStreamingService;

    private final MarkdownService markdownService;

    /**
     * Creates the guided learning controller wired to the guided learning orchestration services.
     */
    public GuidedLearningController(GuidedLearningService guidedService,
                                    ChatMemoryService chatMemory,
                                    OpenAIStreamingService openAIStreamingService,
                                    ExceptionResponseBuilder exceptionBuilder,
                                    MarkdownService markdownService) {
        super(exceptionBuilder);
        this.guidedService = guidedService;
        this.chatMemory = chatMemory;
        this.openAIStreamingService = openAIStreamingService;
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

    /**
     * Retrieves enrichment content (hints, reminders, background) for a specific lesson.
     *
     * @param slug The unique identifier for the lesson.
     * @return An {@link Enrichment} object containing the lesson's enrichment data.
     */
    @GetMapping("/enrich")
    public Enrichment enrich(@RequestParam("slug") String slug) {
        return guidedService.enrichmentForLesson(slug).sanitized();
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
        // If cached, emit immediately as a single-frame stream with proper SSE formatting
        var cached = guidedService.getCachedLessonMarkdown(slug);
        if (cached.isPresent()) {
            String payload = cached.get().replace("\r", "");
            // Return raw content and let Spring handle SSE formatting automatically
            return Flux.just(payload);
        }
        
        // Stream raw content and let Spring handle SSE formatting automatically
        return guidedService.streamLessonContent(slug);
    }

    /**
     * Retrieves the full lesson content as a single JSON object.
     * This is a non-streaming alternative to {@link #streamLesson(String)}.
     *
     * @param slug The unique identifier for the lesson.
     * @return A response containing the markdown content and cache status.
     */
    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public LessonContentResponse content(@RequestParam("slug") String slug) {
        var cached = guidedService.getCachedLessonMarkdown(slug);
        if (cached.isPresent()) {
            return new LessonContentResponse(cached.get(), true);
        }
        // Generate synchronously (best-effort) and cache
        List<String> chunks = guidedService.streamLessonContent(slug).collectList().block(LESSON_CONTENT_TIMEOUT);
        if (chunks == null || chunks.isEmpty()) {
            log.error("Content generation timed out or returned empty for lesson");
            throw new IllegalStateException("Content generation failed for lesson");
        }
        String md = String.join("", chunks);
        guidedService.putLessonCache(slug, md);
        return new LessonContentResponse(md, false);
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
            List<String> chunks = guidedService.streamLessonContent(slug).collectList().block(LESSON_CONTENT_TIMEOUT);
            if (chunks == null || chunks.isEmpty()) {
                log.error("Content generation timed out or returned empty for lesson HTML");
                throw new IllegalStateException("Content generation failed for lesson");
            }
            String text = String.join("", chunks);
            guidedService.putLessonCache(slug, text);
            return text;
        });
        return markdownService.processStructured(md).html();
    }

    /**
     * Streams a response to a user's chat message within the context of a guided lesson.
     *
     * @param request The guided stream request containing sessionId, slug, and user message.
     * @return A {@link Flux} of strings representing the streaming response, sent as SSE data events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody GuidedStreamRequest request, HttpServletResponse response) {
        // Critical proxy headers for streaming
        response.addHeader("X-Accel-Buffering", "no"); // Nginx: disable proxy buffering
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");

        String sessionId = request.resolvedSessionId();
        String userQuery = request.userQuery();
        String lessonSlug = request.lessonSlug();

        chatMemory.addUser(sessionId, userQuery);
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        StringBuilder fullResponse = new StringBuilder();

        // Use OpenAI streaming only (legacy fallback removed)
        if (openAIStreamingService.isAvailable()) {
            // Build the complete prompt using GuidedLearningService logic
            String fullPrompt = guidedService.buildGuidedPromptWithContext(history, lessonSlug, userQuery);
            
            // Clean OpenAI streaming - no manual SSE parsing, no token buffering artifacts
            Flux<String> dataStream = openAIStreamingService.streamResponse(fullPrompt, 0.7)
                    .doOnNext(chunk -> fullResponse.append(chunk))
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .onBackpressureLatest();  // Handle backpressure to prevent memory buildup

            // Heartbeats terminate when data stream completes (success or error).
            // Errors propagate through dataStream to onErrorResume below - no duplicate logging here.
            Flux<String> heartbeats = Flux.interval(Duration.ofSeconds(20))
                    .takeUntilOther(dataStream.ignoreElements())
                    .map(tick -> "");

            return Flux.merge(dataStream, heartbeats)
                    .filter(event -> event != null && !event.isEmpty())  // Filter out empty heartbeat strings
                    .doOnComplete(() -> {
                        // Store processed HTML for consistency with Chat
                        var processed = markdownService.processStructured(fullResponse.toString());
                        chatMemory.addAssistant(sessionId, processed.html());
                    })
                    .onErrorResume(error -> {
                        // Log and send error marker to client - errors must be communicated, not silently dropped
                        log.error("Guided streaming error (exception type: {})",
                            error.getClass().getSimpleName());
                        return Flux.just("[ERROR] Streaming error: " + error.getClass().getSimpleName());
                    });

        } else {
            // Service unavailable - send structured error so client can handle appropriately
            log.warn("OpenAI streaming service unavailable for guided session");
            return Flux.just("[ERROR] Service temporarily unavailable. The streaming service is not ready.");
        }
    }
}
