package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_RETRIEVAL_TIMEOUT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import com.williamcallahan.javachat.model.ChatTurn;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.ConfiguredProviderTemporarilyUnavailableException;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.HybridSearchPartialFailureException;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RerankingFailureException;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import com.williamcallahan.javachat.support.StructuredLogValue;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Exposes chat endpoints for streaming responses, session history management, and diagnostics.
 */
@RestController
@RequestMapping("/api/chat")
@PermitAll
@PreAuthorize("permitAll()")
public class ChatController extends BaseController {
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final String SESSION_ID_REQUIRED = "Session ID is required";
    private static final String SESSION_NOT_FOUND_MESSAGE = "Session not found on server";
    private static final String SESSION_FOUND_MESSAGE = "Session found";
    private static final String SESSION_FOUND_EMPTY_MESSAGE = "Session found but empty";
    private static final String PIPELINE_LOG_SEPARATOR = "============================================";
    private static final int MAX_STREAM_LOG_SESSION_ID_LENGTH = 128;
    private static final String RETRIEVAL_UNAVAILABLE_MESSAGE =
            "Could not search the Java documentation for this question. Please try again.";
    private static final String RETRIEVAL_UNAVAILABLE_DETAILS =
            "Java documentation retrieval failed before response generation.";
    private static final String GENERIC_STREAMING_FAILURE_MESSAGE =
            "Something went wrong while generating this response. Please try again.";
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
    private static final String UNPRESERVABLE_REASONING_INTENT_CODE = "unpreservable_reasoning_intent";

    private final ChatService chatService;
    private final ChatMemoryService chatMemory;
    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    private final SseSupport sseSupport;
    private final AppProperties appProperties;

    /**
     * Creates the chat controller wired to chat, retrieval, and streaming services.
     *
     * @param chatService chat orchestration service
     * @param chatMemory conversation memory service
     * @param openAIStreamingService streaming LLM client
     * @param retrievalService retrieval service for diagnostics
     * @param sseSupport shared SSE serialization and event support
     * @param exceptionBuilder shared exception response builder
     * @param appProperties application configuration
     */
    public ChatController(
            ChatService chatService,
            ChatMemoryService chatMemory,
            OpenAIStreamingService openAIStreamingService,
            RetrievalService retrievalService,
            SseSupport sseSupport,
            ExceptionResponseBuilder exceptionBuilder,
            AppProperties appProperties) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
        this.sseSupport = sseSupport;
        this.appProperties = appProperties;
    }

    /**
     * Normalizes the role from a chat turn for consistent comparison/display.
     */
    private String normalizeRole(ChatTurn turn) {
        return turn.getRole() == null
                ? ""
                : AsciiTextNormalizer.toLowerAscii(turn.getRole().trim());
    }

    /**
     * Streams a response to a user's chat message using Server-Sent Events (SSE).
     * Uses the OpenAI Java SDK for clean, reliable streaming without manual SSE parsing.
     *
     * @param request the chat stream request containing sessionId and user query
     * @return A {@link Flux} of strings representing the streaming response, sent as SSE data events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @Valid @RequestBody ChatStreamRequest request, HttpServletResponse response) {
        sseSupport.configureStreamingHeaders(response);
        long requestToken = REQUEST_SEQUENCE.incrementAndGet();
        long responsePreparationDeadlineNanos =
                System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos();

        String sessionId = request.resolvedSessionId();
        String latest = request.latest();

        PIPELINE_LOG.info("[{}] {}", requestToken, PIPELINE_LOG_SEPARATOR);
        PIPELINE_LOG.info("[{}] NEW CHAT REQUEST", requestToken);
        PIPELINE_LOG.info("[{}] {}", requestToken, PIPELINE_LOG_SEPARATOR);

        // Retrieval progress events stream live from the blocking retrieval work so the client can
        // show which step (library search, rerank) is running during response preparation.
        Sinks.Many<ServerSentEvent<String>> retrievalProgressEvents =
                Sinks.many().multicast().onBackpressureBuffer();

        Flux<ServerSentEvent<String>> operationEvents = Flux.defer(() -> {
                    // Avoid reading session state or performing retrieval when the configured provider
                    // cannot stream.
                    if (!openAIStreamingService.isAvailable()) {
                        PIPELINE_LOG.warn("[{}] OpenAI streaming service unavailable", requestToken);
                        return sseSupport.configuredProviderConfigurationError();
                    }
                    if (!openAIStreamingService.canAttemptRequest()) {
                        PIPELINE_LOG.warn("[{}] Configured provider temporarily unavailable", requestToken);
                        return sseSupport.configuredProviderUnavailableError();
                    }
                    List<Message> history = chatMemory.getHistory(sessionId);
                    PIPELINE_LOG.info("[{}] Chat history loaded", requestToken);

                    // Build structured prompt for intelligent truncation.
                    ChatService.StructuredPromptOutcome promptOutcome =
                            chatService.buildStructuredPromptWithContextOutcome(
                                    history,
                                    latest,
                                    retrievalNotice -> {
                                        // A failed emission only means the client went away; progress
                                        // notices are diagnostics and must not fail retrieval.
                                        retrievalProgressEvents.tryEmitNext(sseSupport.statusEvent(
                                                retrievalNotice.summary(), retrievalNotice.details()));
                                    },
                                    responsePreparationDeadlineNanos);

                    // Use OpenAI streaming only (legacy fallback removed)
                    StringBuilder fullResponse = new StringBuilder();
                    AtomicInteger chunkCount = new AtomicInteger(0);
                    PIPELINE_LOG.info("[{}] Using OpenAI Java SDK for streaming (structured prompt)", requestToken);

                    // Stream with provider transparency - surfaces which LLM is responding
                    return openAIStreamingService
                            .streamResponse(
                                    promptOutcome.structuredPrompt(),
                                    appProperties.getLlm().getTemperature())
                            .flatMapMany(streamingResult -> {
                                RetrievalService.CitationOutcome citationOutcome =
                                        chatService.citationOutcomeForRetainedContext(
                                                latest, promptOutcome, streamingResult.contextDocumentIds());
                                List<Citation> finalCitations = citationOutcome.citations();

                                // Provider event first - surfaces which LLM is handling this request
                                ServerSentEvent<String> providerEvent =
                                        sseSupport.providerEvent(streamingResult.providerDisplayName());

                                // Stream with structure-aware truncation - preserves semantic
                                // boundaries
                                Flux<String> answerChunks = sseSupport.appendSourceAvailabilityNote(
                                        streamingResult.textChunks(),
                                        !streamingResult.contextDocumentIds().isEmpty());
                                Flux<String> dataStream = sseSupport.prepareDataStream(answerChunks, chunk -> {
                                    fullResponse.append(chunk);
                                    chunkCount.incrementAndGet();
                                });

                                Flux<ServerSentEvent<String>> statusEvents =
                                        sseSupport.citationPartialFailureStatusFlux(
                                                citationOutcome.failedConversionCount());

                                // Wrap chunks in JSON to preserve whitespace
                                Flux<ServerSentEvent<String>> dataEvents = dataStream.map(sseSupport::textEvent);

                                Flux<ServerSentEvent<String>> citationEvent =
                                        Flux.just(sseSupport.citationEvent(finalCitations));

                                // Start selected-provider and status events before the ref-counted data
                                // stream.
                                return Flux.concat(Flux.just(providerEvent), statusEvents, dataEvents, citationEvent);
                            })
                            .doOnComplete(() -> {
                                chatMemory.addExchange(sessionId, latest, fullResponse.toString());
                                PIPELINE_LOG.info("[{}] STREAMING COMPLETE", requestToken);
                            });
                })
                .subscribeOn(Schedulers.boundedElastic());
        Flux<ServerSentEvent<String>> deadlineBoundOperationEvents =
                sseSupport.enforceResponsePreparationDeadline(operationEvents, responsePreparationDeadlineNanos);
        Flux<ServerSentEvent<String>> operationEventsWithProgress = Flux.merge(
                retrievalProgressEvents.asFlux(),
                deadlineBoundOperationEvents.doFinally(terminationSignal -> retrievalProgressEvents.tryEmitComplete()));
        return Flux.concat(
                        sseSupport.responsePreparationStatus(), sseSupport.withHeartbeats(operationEventsWithProgress))
                .onErrorResume(error -> {
                    Optional<ReportedStreamingFailure> terminalFailureContext =
                            ReportedStreamingFailure.findInCauseChain(error);
                    Throwable upstreamError = terminalFailureContext
                            .map(ReportedStreamingFailure::upstreamFailure)
                            .orElse(error);
                    if (upstreamError instanceof ConfiguredProviderTemporarilyUnavailableException) {
                        return sseSupport.configuredProviderUnavailableError();
                    }
                    if (terminalFailureContext.isEmpty() && sseSupport.isResponsePreparationTimeout(upstreamError)) {
                        PIPELINE_LOG
                                .atWarn()
                                .setMessage("Response preparation timeout")
                                .addKeyValue("requestToken", requestToken)
                                .addKeyValue(
                                        "sessionId",
                                        StructuredLogValue.bounded(sessionId, MAX_STREAM_LOG_SESSION_ID_LENGTH)
                                                .text())
                                .addKeyValue("code", STATUS_CODE_RETRIEVAL_TIMEOUT)
                                .addKeyValue("stage", STATUS_STAGE_RETRIEVAL)
                                .addKeyValue(
                                        "exceptionType",
                                        upstreamError.getClass().getSimpleName())
                                .log();
                        return sseSupport.responsePreparationTimeoutError();
                    }
                    String errorDetail = buildUserFacingErrorMessage(upstreamError);
                    String diagnostics = isRetrievalFailure(upstreamError)
                            ? RETRIEVAL_UNAVAILABLE_DETAILS
                            : upstreamError instanceof Exception exception
                                    ? describeException(exception)
                                    : upstreamError.getClass().getName();
                    if (terminalFailureContext.isEmpty()) {
                        PIPELINE_LOG
                                .atError()
                                .setMessage("[{}] STREAMING ERROR")
                                .addArgument(requestToken)
                                .addKeyValue(
                                        "sessionId",
                                        StructuredLogValue.bounded(sessionId, MAX_STREAM_LOG_SESSION_ID_LENGTH)
                                                .text())
                                .addKeyValue("exceptionType", error.getClass().getSimpleName())
                                .log();
                    }
                    boolean retryable = openAIStreamingService.isRecoverableStreamingFailure(error);
                    return sseSupport.streamErrorEvent(errorDetail, diagnostics, retryable);
                });
    }

    /**
     * Diagnostics: Return the RAG retrieval context for a given query.
     * Dev-only usage in UI; kept simple and safe.
     */
    @GetMapping("/diagnostics/retrieval")
    public RetrievalDiagnosticsResponse retrievalDiagnostics(@RequestParam("q") String query) {
        List<Document> retrievalDocuments = chatService.retrieveTokenConstrainedOfficialDocumentation(query);
        // Normalize URLs the same way as citations so we never emit file:// links
        List<Citation> citations =
                retrievalService.toCitationsForQuery(query, retrievalDocuments).citations();
        return RetrievalDiagnosticsResponse.success(citations);
    }

    /**
     * Retrieves a list of relevant citations for a given query.
     *
     * @param query The search query string.
     * @return A {@link List} of {@link Citation} objects.
     */
    @GetMapping("/citations")
    public List<Citation> citations(@RequestParam("q") String query) {
        return chatService.citationsFor(query);
    }

    /**
     * Exports the last assistant message from a given chat session.
     *
     * @param sessionId The ID of the chat session (required).
     * @return The last assistant message or appropriate HTTP error.
     */
    @GetMapping(value = "/export/last", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportLast(@RequestParam(name = "sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(SESSION_ID_REQUIRED);
        }
        var turns = chatMemory.getTurns(sessionId);
        for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
            var turn = turns.get(turnIndex);
            if ("assistant".equals(normalizeRole(turn))) {
                return ResponseEntity.ok(turn.getText());
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No assistant message found in session: " + sessionId);
    }

    /**
     * Exports the entire history of a chat session as a formatted string.
     *
     * @param sessionId The ID of the chat session (required).
     * @return The full conversation or appropriate HTTP error.
     */
    @GetMapping(value = "/export/session", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportSession(@RequestParam(name = "sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(SESSION_ID_REQUIRED);
        }
        var turns = chatMemory.getTurns(sessionId);
        if (turns.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No history found for session: " + sessionId);
        }
        StringBuilder formatted = new StringBuilder();
        for (var turn : turns) {
            String role = "user".equals(normalizeRole(turn)) ? "User" : "Assistant";
            formatted
                    .append("### ")
                    .append(role)
                    .append("\n\n")
                    .append(turn.getText())
                    .append("\n\n");
        }
        return ResponseEntity.ok(formatted.toString());
    }

    /**
     * Clears the chat history for a given session.
     *
     * @param sessionId The ID of the chat session. Defaults to "default".
     * @return A simple success message.
     */
    @PostMapping("/clear")
    public ResponseEntity<String> clearSession(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body("No session ID provided");
        }
        chatMemory.clear(sessionId);
        PIPELINE_LOG.info("Cleared chat session");
        return ResponseEntity.ok("Session cleared");
    }

    /**
     * Validates session state for frontend synchronization.
     * Returns the server-side message count so frontends can detect drift after server restarts.
     *
     * @param sessionId The ID of the chat session to validate.
     * @return Session validation info including message count.
     */
    @GetMapping("/session/validate")
    public ResponseEntity<SessionValidationResponse> validateSession(
            @RequestParam(name = "sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.badRequest().body(new SessionValidationResponse("", 0, false, SESSION_ID_REQUIRED));
        }
        boolean sessionRecognized = chatMemory.hasSession(sessionId);
        if (!sessionRecognized) {
            return ResponseEntity.ok(new SessionValidationResponse(sessionId, 0, false, SESSION_NOT_FOUND_MESSAGE));
        }
        var turns = chatMemory.getTurns(sessionId);
        int turnCount = turns.size();
        boolean exists = turnCount > 0;
        String validationMessage = exists ? SESSION_FOUND_MESSAGE : SESSION_FOUND_EMPTY_MESSAGE;
        return ResponseEntity.ok(new SessionValidationResponse(sessionId, turnCount, exists, validationMessage));
    }

    /**
     * Builds a user-facing error message with provider context when possible.
     * Rate limit and IO errors include enough detail for users to understand which service failed.
     */
    private String buildUserFacingErrorMessage(Throwable error) {
        if (error instanceof RateLimitException rateLimitError) {
            // Extract provider from the exception message or headers if possible
            String message = rateLimitError.getMessage();
            if (message != null && message.contains("429")) {
                return "Rate limit reached - LLM provider returned 429. Please wait before retrying.";
            }
            return "Rate limit reached - " + error.getClass().getSimpleName();
        }

        if (error instanceof OpenAIIoException ioError) {
            Throwable cause = ioError.getCause();
            if (cause != null
                    && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("interrupt")) {
                return "Request cancelled - LLM provider did not respond in time";
            }
            return "LLM provider connection failed - " + error.getClass().getSimpleName();
        }

        if (error instanceof IllegalStateException
                && error.getMessage() != null
                && error.getMessage().contains("providers unavailable")) {
            return error.getMessage();
        }

        if (isRetrievalFailure(error)) {
            return RETRIEVAL_UNAVAILABLE_MESSAGE;
        }

        if (isUnpreservableReasoningIntent(error)) {
            return unpreservableReasoningIntentMessage();
        }

        return GENERIC_STREAMING_FAILURE_MESSAGE;
    }

    private static boolean isUnpreservableReasoningIntent(Throwable error) {
        return error instanceof OpenAIServiceException serviceException
                && serviceException.statusCode() == HTTP_UNPROCESSABLE_ENTITY
                && serviceException
                        .code()
                        .map(UNPRESERVABLE_REASONING_INTENT_CODE::equals)
                        .orElse(false);
    }

    /**
     * Names the operator-facing fix for a deterministic gateway rejection: the configured
     * reasoning effort cannot be preserved by any provider for this model, so retrying unchanged
     * can never succeed; only lowering or unsetting {@code app.llm.reasoning-effort} resolves it.
     */
    private String unpreservableReasoningIntentMessage() {
        String configuredReasoningEffort = appProperties.getLlm().getReasoningEffort();
        String rejectedEffort = configuredReasoningEffort == null || configuredReasoningEffort.isBlank()
                ? "the configured reasoning effort"
                : "reasoning effort '" + configuredReasoningEffort.trim() + "'";
        return "The gateway cannot preserve " + rejectedEffort + " for this model (HTTP 422 "
                + UNPRESERVABLE_REASONING_INTENT_CODE + "). Lower or unset app.llm.reasoning-effort to resolve;"
                + " retrying unchanged cannot succeed.";
    }

    private static boolean isRetrievalFailure(Throwable error) {
        return error instanceof EmbeddingServiceUnavailableException
                || error instanceof HybridSearchPartialFailureException
                || error instanceof RerankingFailureException;
    }
}
