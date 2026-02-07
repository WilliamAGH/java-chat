package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.*;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.errors.ApiResponse;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
    private final RetrievalService retrievalService;
    private final ChatMemoryService chatMemory;
    private final OpenAIStreamingService openAIStreamingService;
    private final MarkdownService markdownService;
    private final SseSupport sseSupport;
    private final AppProperties appProperties;

    /**
     * Creates the guided learning controller wired to the guided learning orchestration services.
     */
    public GuidedLearningController(
            GuidedLearningService guidedService,
            RetrievalService retrievalService,
            ChatMemoryService chatMemory,
            OpenAIStreamingService openAIStreamingService,
            ExceptionResponseBuilder exceptionBuilder,
            MarkdownService markdownService,
            SseSupport sseSupport,
            AppProperties appProperties) {
        super(exceptionBuilder);
        this.guidedService = guidedService;
        this.retrievalService = retrievalService;
        this.chatMemory = chatMemory;
        this.openAIStreamingService = openAIStreamingService;
        this.markdownService = markdownService;
        this.sseSupport = sseSupport;
        this.appProperties = appProperties;
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
        return guidedService
                .getLesson(slug)
                .orElseThrow(() -> new NoSuchElementException("Unknown lesson slug: " + slug));
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
        return guidedService
                .getCachedLessonMarkdown(slug)
                .map(cachedMarkdown -> Flux.just(cachedMarkdown.replace("\r", "")))
                .orElseGet(() -> guidedService.streamLessonContent(slug));
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
        return guidedService
                .getCachedLessonMarkdown(slug)
                .map(cachedMarkdown -> new LessonContentResponse(cachedMarkdown, true))
                .orElseGet(() -> new LessonContentResponse(generateAndCacheLessonContent(slug), false));
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
        String lessonMarkdownContent = cached.orElseGet(() -> generateAndCacheLessonContent(slug));
        return markdownService.processStructured(lessonMarkdownContent).html();
    }

    /**
     * Generates lesson content synchronously and caches the result.
     *
     * @param slug lesson identifier
     * @return generated markdown content
     * @throws IllegalStateException if generation times out or returns empty
     */
    private String generateAndCacheLessonContent(String slug) {
        List<String> chunks =
                guidedService.streamLessonContent(slug).collectList().block(LESSON_CONTENT_TIMEOUT);
        if (chunks == null || chunks.isEmpty()) {
            log.error("Content generation timed out or returned empty for lesson");
            throw new IllegalStateException("Content generation failed for lesson");
        }
        String lessonMarkdown = String.join("", chunks);
        guidedService.putLessonCache(slug, lessonMarkdown);
        return lessonMarkdown;
    }

    /**
     * Streams a response to a user's chat message within the context of a guided lesson.
     * Uses the same JSON-wrapped SSE format as ChatController for consistent whitespace handling.
     *
     * @param request The guided stream request containing sessionId, slug, and user message.
     * @return A {@link Flux} of ServerSentEvents with JSON-wrapped text chunks.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestBody GuidedStreamRequest request, HttpServletResponse response) {
        sseSupport.configureStreamingHeaders(response);

        String sessionId = request.resolvedSessionId();
        String userQuery =
                request.userQuery().orElseThrow(() -> new IllegalArgumentException("User query is required"));
        String lessonSlug =
                request.lessonSlug().orElseThrow(() -> new IllegalArgumentException("Lesson slug is required"));

        if (!openAIStreamingService.isAvailable()) {
            log.warn("OpenAI streaming service unavailable for guided session");
            return sseSupport.sseError("Service temporarily unavailable", "The streaming service is not ready");
        }

        return streamGuidedResponse(sessionId, userQuery, lessonSlug);
    }

    /**
     * Builds and streams the guided lesson response via OpenAI.
     *
     * @param sessionId chat session identifier
     * @param userQuery user's question
     * @param lessonSlug lesson context identifier
     * @return SSE stream of response chunks with heartbeats
     */
    private Flux<ServerSentEvent<String>> streamGuidedResponse(String sessionId, String userQuery, String lessonSlug) {
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        chatMemory.addUser(sessionId, userQuery);
        StringBuilder fullResponse = new StringBuilder();

        GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                guidedService.buildStructuredGuidedPromptWithContext(history, lessonSlug, userQuery);

        // Compute citations before streaming starts - page-anchor failures surfaced to UI via status event
        List<Citation> computedCitations;
        String citationWarning;
        try {
            computedCitations = guidedService.citationsForBookDocuments(promptOutcome.bookContextDocuments());
            citationWarning = null;
        } catch (RuntimeException citationEnhancementFailure) {
            log.warn(
                    "PDF page-anchor enhancement failed; returning base citations (exceptionType={})",
                    citationEnhancementFailure.getClass().getSimpleName());
            computedCitations = retrievalService
                    .toCitations(promptOutcome.bookContextDocuments())
                    .citations();
            citationWarning = "PDF page-anchor enhancement failed; using base citations without page anchors";
        }
        final List<Citation> finalCitations = computedCitations;
        final String finalCitationWarning = citationWarning;

        // Stream with provider transparency - surfaces which LLM is responding
        return openAIStreamingService
                .streamResponse(
                        promptOutcome.structuredPrompt(), appProperties.getLlm().getTemperature())
                .flatMapMany(streamingResult -> {
                    // Provider event first - surfaces which LLM is handling this request
                    ServerSentEvent<String> providerEvent =
                            sseSupport.providerEvent(streamingResult.providerDisplayName());

                    Flux<String> dataStream =
                            sseSupport.prepareDataStream(streamingResult.textChunks(), fullResponse::append);

                    Flux<ServerSentEvent<String>> heartbeats = sseSupport.heartbeats(dataStream);
                    Flux<ServerSentEvent<String>> dataEvents = dataStream.map(sseSupport::textEvent);
                    Flux<ServerSentEvent<String>> citationEvent = Flux.just(sseSupport.citationEvent(finalCitations));

                    Flux<ServerSentEvent<String>> statusEvents =
                            sseSupport.citationWarningStatusFlux(finalCitationWarning);
                    Flux<ServerSentEvent<String>> runtimeStreamingStatusEvents =
                            sseSupport.streamingNoticeEvents(streamingResult.notices());

                    return Flux.concat(
                            Flux.just(providerEvent),
                            statusEvents,
                            Flux.merge(dataEvents, heartbeats, runtimeStreamingStatusEvents),
                            citationEvent);
                })
                .doOnComplete(() -> chatMemory.addAssistant(sessionId, fullResponse.toString()))
                .onErrorResume(error -> {
                    log.error(
                            "Guided streaming error (sessionId={}, lessonSlug={}, exceptionType={})",
                            sessionId,
                            lessonSlug,
                            error.getClass().getSimpleName(),
                            error);
                    boolean retryable = openAIStreamingService.isRecoverableStreamingFailure(error);
                    return sseSupport.streamErrorEvent(
                            "Streaming error: " + error.getClass().getSimpleName(),
                            "The response stream encountered an error. Please try again.",
                            retryable);
                });
    }

    /**
     * Maps validation exceptions from missing request fields to HTTP 400 responses.
     *
     * @param validationException the validation exception with the error details
     * @return standardized bad request error response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleValidationException(IllegalArgumentException validationException) {
        return super.handleValidationException(validationException);
    }
}
