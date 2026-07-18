package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.domain.errors.ApiResponse;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.model.GuidedLesson;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ConfiguredProviderTemporarilyUnavailableException;
import com.williamcallahan.javachat.service.GuidedLearningService;
import com.williamcallahan.javachat.service.MarkdownService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.support.StructuredLogValue;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Exposes guided learning endpoints for lesson metadata, citations, enrichment, and streaming lesson content.
 */
@RestController
@RequestMapping("/api/guided")
@PermitAll
@PreAuthorize("permitAll()")
public class GuidedLearningController extends BaseController {
    private static final int MAX_GUIDED_LOG_FIELD_LENGTH = 256;
    private static final String GUIDED_CHAT_STREAM_ERROR_MESSAGE = "Streaming error";
    private static final String GUIDED_CHAT_STREAM_ERROR_DETAILS =
            "The response stream encountered an error. Please try again.";
    private static final String CURATED_LESSON_RESOURCE_FAILURE_MESSAGE = "Curated lesson content is unavailable";
    private static final String CURATED_LESSON_RESOURCE_FAILURE_LOG_MESSAGE =
            "Guided lesson is listed in the TOC but has no packaged markdown";
    private static final String UNKNOWN_LESSON_SLUG_MESSAGE = "Unknown lesson slug: ";
    private static final Logger log = LoggerFactory.getLogger(GuidedLearningController.class);

    /** Timeout for synchronous curated lesson content reads. */
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
     * @throws ResponseStatusException when the slug is not listed in the guided table of contents.
     */
    @GetMapping("/lesson")
    public GuidedLesson lesson(@RequestParam("slug") String slug) {
        return requireListedLesson(slug);
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
     * @param response servlet response used to disable proxy buffering.
     * @return A {@link Flux} of SSE text events carrying lesson markdown chunks.
     */
    @GetMapping(value = "/content/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamLesson(@RequestParam("slug") String slug, HttpServletResponse response) {
        Flux<String> lessonContentStream = curatedLessonContentStream(slug);
        sseSupport.configureStreamingHeaders(response);
        return Flux.defer(() -> {
                    Flux<String> dataStream = sseSupport.prepareDataStream(lessonContentStream, ignoredChunk -> {});
                    return Flux.merge(dataStream.map(sseSupport::textEvent), sseSupport.heartbeats(dataStream));
                })
                .onErrorResume(error -> {
                    if (ReportedStreamingFailure.findInCauseChain(error).isEmpty()) {
                        log.atError()
                                .setMessage("Guided lesson content stream error")
                                .addKeyValue(
                                        "lessonSlug",
                                        StructuredLogValue.bounded(slug, MAX_GUIDED_LOG_FIELD_LENGTH)
                                                .text())
                                .addKeyValue("exceptionType", error.getClass().getSimpleName())
                                .log();
                    }
                    boolean retryable = openAIStreamingService.isRecoverableStreamingFailure(error);
                    return sseSupport.streamErrorEvent(
                            "Lesson content stream failed",
                            "The lesson content stream encountered an error. Please try again.",
                            retryable);
                });
    }

    /**
     * Retrieves the full lesson content as a single JSON object.
     * This is a non-streaming alternative to {@link #streamLesson(String)}.
     *
     * @param slug The unique identifier for the lesson.
     * @return A response containing the authoritative curated markdown content.
     */
    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public LessonContentResponse content(@RequestParam("slug") String slug) {
        return new LessonContentResponse(readCuratedLessonContent(slug), false);
    }

    /**
     * Retrieves the lesson content rendered as HTML.
     * The authoritative curated markdown is loaded and then rendered by the server.
     *
     * @param slug The unique identifier for the lesson.
     * @return A string containing the rendered HTML.
     */
    @GetMapping(value = "/content/html", produces = MediaType.TEXT_HTML_VALUE)
    public String contentHtml(@RequestParam("slug") String slug) {
        String lessonMarkdownContent = readCuratedLessonContent(slug);
        return markdownService.processStructured(lessonMarkdownContent).html();
    }

    /**
     * Loads curated lesson content synchronously for non-streaming endpoints.
     *
     * @param slug lesson identifier
     * @return curated markdown content
     * @throws IllegalStateException if the curated stream returns empty
     */
    private String readCuratedLessonContent(String slug) {
        List<String> lessonMarkdownChunks =
                curatedLessonContentStream(slug).collectList().block(LESSON_CONTENT_TIMEOUT);
        if (lessonMarkdownChunks == null || lessonMarkdownChunks.isEmpty()) {
            log.error("Curated lesson stream returned empty content");
            throw new IllegalStateException("Curated lesson content is empty");
        }
        return String.join("", lessonMarkdownChunks);
    }

    /**
     * Resolves a curated lesson stream before the HTTP response commits so missing resources surface as 5xx.
     */
    private Flux<String> curatedLessonContentStream(String slug) {
        requireListedLesson(slug);
        try {
            return guidedService.streamLessonContent(slug);
        } catch (GuidedLearningService.CuratedLessonResourceMissingException missingResourceFailure) {
            log.atError()
                    .setMessage(CURATED_LESSON_RESOURCE_FAILURE_LOG_MESSAGE)
                    .setCause(missingResourceFailure)
                    .addKeyValue(
                            "lessonSlug",
                            StructuredLogValue.bounded(slug, MAX_GUIDED_LOG_FIELD_LENGTH)
                                    .text())
                    .log();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, CURATED_LESSON_RESOURCE_FAILURE_MESSAGE, missingResourceFailure);
        }
    }

    /** Resolves a lesson or maps its absence to the public guided-content 404 contract. */
    private GuidedLesson requireListedLesson(String slug) {
        return guidedService
                .getLesson(slug)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        UNKNOWN_LESSON_SLUG_MESSAGE
                                + StructuredLogValue.bounded(slug, MAX_GUIDED_LOG_FIELD_LENGTH)
                                        .text()));
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
            @Valid @RequestBody GuidedStreamRequest request, HttpServletResponse response) {
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
        List<org.springframework.ai.chat.messages.Message> history = chatMemory.getHistory(sessionId);
        return Flux.defer(() -> {
                    StringBuilder fullResponse = new StringBuilder();

                    GuidedLearningService.GuidedChatPromptOutcome promptOutcome =
                            guidedService.buildStructuredGuidedPromptWithContext(history, lessonSlug, userQuery);

                    // Compute citations before streaming starts - page-anchor failures surfaced to UI via status event
                    List<Citation> computedCitations;
                    String citationWarning;
                    try {
                        computedCitations =
                                guidedService.citationsForBookDocuments(promptOutcome.bookContextDocuments());
                        citationWarning = null;
                    } catch (RuntimeException citationEnhancementFailure) {
                        log.warn(
                                "PDF page-anchor enhancement failed; returning base citations (exceptionType={})",
                                citationEnhancementFailure.getClass().getSimpleName());
                        computedCitations = retrievalService
                                .toCitations(promptOutcome.bookContextDocuments())
                                .citations();
                        citationWarning =
                                "PDF page-anchor enhancement failed; using base citations without page anchors";
                    }
                    final List<Citation> finalCitations = computedCitations;
                    final String finalCitationWarning = citationWarning;

                    // Stream with provider transparency - surfaces which LLM is responding
                    return openAIStreamingService
                            .streamResponse(
                                    promptOutcome.structuredPrompt(),
                                    appProperties.getLlm().getTemperature())
                            .flatMapMany(streamingResult -> {
                                // Provider event first - surfaces which LLM is handling this request
                                ServerSentEvent<String> providerEvent =
                                        sseSupport.providerEvent(streamingResult.providerDisplayName());

                                Flux<String> dataStream = sseSupport.prepareDataStream(
                                        streamingResult.textChunks(), fullResponse::append);

                                Flux<ServerSentEvent<String>> heartbeats = sseSupport.heartbeats(dataStream);
                                Flux<ServerSentEvent<String>> dataEvents = dataStream.map(sseSupport::textEvent);
                                Flux<ServerSentEvent<String>> citationEvent =
                                        Flux.just(sseSupport.citationEvent(finalCitations));

                                Flux<ServerSentEvent<String>> statusEvents =
                                        sseSupport.citationWarningStatusFlux(finalCitationWarning);

                                // Start selected-provider and status events before the ref-counted data stream.
                                return Flux.concat(
                                        Flux.just(providerEvent),
                                        statusEvents,
                                        Flux.merge(dataEvents, heartbeats),
                                        citationEvent);
                            })
                            .doOnComplete(() -> chatMemory.addExchange(sessionId, userQuery, fullResponse.toString()));
                })
                .onErrorResume(error -> {
                    Optional<ReportedStreamingFailure> terminalFailureContext =
                            ReportedStreamingFailure.findInCauseChain(error);
                    Throwable upstreamError = terminalFailureContext
                            .map(ReportedStreamingFailure::upstreamFailure)
                            .orElse(error);
                    if (upstreamError instanceof ConfiguredProviderTemporarilyUnavailableException) {
                        return sseSupport.configuredProviderUnavailableError();
                    }
                    if (terminalFailureContext.isEmpty()) {
                        log.atError()
                                .setMessage("Guided streaming error")
                                .addKeyValue(
                                        "sessionId",
                                        StructuredLogValue.bounded(sessionId, MAX_GUIDED_LOG_FIELD_LENGTH)
                                                .text())
                                .addKeyValue(
                                        "lessonSlug",
                                        StructuredLogValue.bounded(lessonSlug, MAX_GUIDED_LOG_FIELD_LENGTH)
                                                .text())
                                .addKeyValue("exceptionType", error.getClass().getSimpleName())
                                .log();
                    }
                    boolean retryable = openAIStreamingService.isRecoverableStreamingFailure(error);
                    return sseSupport.streamErrorEvent(
                            GUIDED_CHAT_STREAM_ERROR_MESSAGE, GUIDED_CHAT_STREAM_ERROR_DETAILS, retryable);
                });
    }

    /**
     * Maps validation exceptions from missing request fields to HTTP 400 responses.
     *
     * @param validationException the validation exception with the error details
     * @return standardized bad request error response
     */
    @Override
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleValidationException(IllegalArgumentException validationException) {
        return super.handleValidationException(validationException);
    }
}
