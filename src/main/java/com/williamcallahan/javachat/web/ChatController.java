package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.*;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.model.ChatTurn;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

/**
 * Exposes chat endpoints for streaming responses, session history management, and diagnostics.
 */
@RestController
@RequestMapping("/api/chat")
@PermitAll
@PreAuthorize("permitAll()")
public class ChatController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("PIPELINE");
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final ChatService chatService;
    private final ChatMemoryService chatMemory;
    private final OpenAIStreamingService openAIStreamingService;
    private final RetrievalService retrievalService;
    private final SseSupport sseSupport;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    /**
     * Creates the chat controller wired to chat, retrieval, and streaming services.
     *
     * @param chatService chat orchestration service
     * @param chatMemory conversation memory service
     * @param openAIStreamingService streaming LLM client
     * @param retrievalService retrieval service for diagnostics
     * @param sseSupport shared SSE serialization and event support
     * @param restTemplateBuilder builder for creating the RestTemplate
     * @param exceptionBuilder shared exception response builder
     * @param appProperties centralized application configuration
     */
    public ChatController(
            ChatService chatService,
            ChatMemoryService chatMemory,
            OpenAIStreamingService openAIStreamingService,
            RetrievalService retrievalService,
            SseSupport sseSupport,
            RestTemplateBuilder restTemplateBuilder,
            ExceptionResponseBuilder exceptionBuilder,
            AppProperties appProperties) {
        super(exceptionBuilder);
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.openAIStreamingService = openAIStreamingService;
        this.retrievalService = retrievalService;
        this.sseSupport = sseSupport;
        this.restTemplate = restTemplateBuilder.build();
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
     * @param body A JSON object containing the user's request. Expected format:
     *             <pre>{@code
     *               {
     *                 "sessionId": "some-session-id", // Optional, defaults to "default"
     *                 "latest": "The user's question?"   // The user's message
     *               }
     *             }</pre>
     * @return A {@link Flux} of strings representing the streaming response, sent as SSE data events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatStreamRequest request, HttpServletResponse response) {
        sseSupport.configureStreamingHeaders(response);
        long requestToken = REQUEST_SEQUENCE.incrementAndGet();

        String sessionId = request.resolvedSessionId();
        String userQuery = request.userQuery();

        PIPELINE_LOG.info("[{}] ============================================", requestToken);
        PIPELINE_LOG.info("[{}] NEW CHAT REQUEST", requestToken);
        PIPELINE_LOG.info("[{}] ============================================", requestToken);

        List<Message> history = new ArrayList<>(chatMemory.getHistory(sessionId));
        PIPELINE_LOG.info("[{}] Chat history loaded", requestToken);

        chatMemory.addUser(sessionId, userQuery);
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger chunkCount = new AtomicInteger(0);

        // Build structured prompt for intelligent truncation
        // Pass model hint to optimize RAG for token-constrained models
        ChatService.StructuredPromptOutcome promptOutcome = chatService.buildStructuredPromptWithContextOutcome(
                history, userQuery, ModelConfiguration.DEFAULT_MODEL);

        // Use OpenAI streaming only (legacy fallback removed)
        if (openAIStreamingService.isAvailable()) {
            PIPELINE_LOG.info("[{}] Using OpenAI Java SDK for streaming (structured prompt)", requestToken);

            // Emit citations inline at stream end - compute before streaming starts
            // Citation conversion errors are surfaced to UI via status event, not silently swallowed
            List<Citation> citations;
            String citationWarning;
            try {
                citations = retrievalService.toCitations(promptOutcome.documents());
                citationWarning = null;
            } catch (Exception citationError) {
                PIPELINE_LOG.warn("[{}] Citation conversion failed: {}", requestToken, citationError.getMessage());
                citations = List.of();
                citationWarning = "Citation retrieval failed - sources unavailable for this response";
            }
            final List<Citation> finalCitations = citations;
            final String finalCitationWarning = citationWarning;

            // Stream with provider transparency - surfaces which LLM is responding
            return openAIStreamingService
                    .streamResponse(promptOutcome.structuredPrompt(), DEFAULT_TEMPERATURE)
                    .flatMapMany(streamingResult -> {
                        // Provider event first - surfaces which LLM is handling this request
                        ServerSentEvent<String> providerEvent =
                                sseSupport.providerEvent(streamingResult.providerDisplayName());

                        // Stream with structure-aware truncation - preserves semantic boundaries
                        Flux<String> dataStream = sseSupport.prepareDataStream(streamingResult.content(), chunk -> {
                            fullResponse.append(chunk);
                            chunkCount.incrementAndGet();
                        });

                        // Heartbeats terminate when data stream completes (success or error)
                        Flux<ServerSentEvent<String>> heartbeats = sseSupport.heartbeats(dataStream);

                        // Combine retrieval notices with citation warning if present
                        Flux<ServerSentEvent<String>> statusEvents = Flux.fromIterable(promptOutcome.notices())
                                .map(notice -> sseSupport.statusEvent(notice.summary(), notice.details()));
                        if (finalCitationWarning != null) {
                            statusEvents = statusEvents.concatWith(Flux.just(
                                    sseSupport.statusEvent(finalCitationWarning, "Citations could not be loaded")));
                        }

                        // Wrap chunks in JSON to preserve whitespace
                        Flux<ServerSentEvent<String>> dataEvents = dataStream.map(sseSupport::textEvent);

                        Flux<ServerSentEvent<String>> citationEvent =
                                Flux.just(sseSupport.citationEvent(finalCitations));

                        return Flux.concat(
                                Flux.just(providerEvent),
                                statusEvents,
                                Flux.merge(dataEvents, heartbeats),
                                citationEvent);
                    })
                    .doOnComplete(() -> {
                        chatMemory.addAssistant(sessionId, fullResponse.toString());
                        PIPELINE_LOG.info("[{}] STREAMING COMPLETE", requestToken);
                    })
                    .onErrorResume(error -> {
                        // Log and send error event to client - errors must be communicated, not silently dropped
                        String errorDetail = buildUserFacingErrorMessage(error);
                        String diagnostics =
                                error instanceof Exception exception ? describeException(exception) : error.toString();
                        PIPELINE_LOG.error("[{}] STREAMING ERROR", requestToken);
                        return sseSupport.sseError(errorDetail, diagnostics);
                    });

        } else {
            // Service unavailable - send structured error event so client can handle appropriately
            PIPELINE_LOG.warn("[{}] OpenAI streaming service unavailable", requestToken);
            return sseSupport.sseError("Streaming service unavailable", "OpenAI streaming service is not ready");
        }
    }

    /**
     * Diagnostics: Return the RAG retrieval context for a given query.
     * Dev-only usage in UI; kept simple and safe.
     */
    @GetMapping("/diagnostics/retrieval")
    public RetrievalDiagnosticsResponse retrievalDiagnostics(@RequestParam("q") String query) {
        // Mirror token-constrained model constraints used in buildPromptWithContext
        RetrievalService.RetrievalOutcome outcome = retrievalService.retrieveWithLimitOutcome(
                query, ModelConfiguration.RAG_LIMIT_CONSTRAINED, ModelConfiguration.RAG_TOKEN_LIMIT_CONSTRAINED);
        // Normalize URLs the same way as citations so we never emit file:// links
        List<Citation> citations = retrievalService.toCitations(outcome.documents());
        if (outcome.notices().isEmpty()) {
            return RetrievalDiagnosticsResponse.success(citations);
        }
        String noticeDetails = outcome.notices().stream()
                .map(notice -> notice.summary() + ": " + notice.details())
                .reduce((first, second) -> first + "; " + second)
                .orElse("Retrieval warnings present");
        return new RetrievalDiagnosticsResponse(citations, noticeDetails);
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
            return ResponseEntity.badRequest().body("Session ID is required");
        }
        var turns = chatMemory.getTurns(sessionId);
        for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
            var turn = turns.get(turnIndex);
            if ("assistant".equals(normalizeRole(turn))) {
                return ResponseEntity.ok(turn.getText());
            }
        }
        return ResponseEntity.status(404).body("No assistant message found in session: " + sessionId);
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
            return ResponseEntity.badRequest().body("Session ID is required");
        }
        var turns = chatMemory.getTurns(sessionId);
        if (turns.isEmpty()) {
            return ResponseEntity.status(404).body("No history found for session: " + sessionId);
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
            return ResponseEntity.badRequest()
                    .body(new SessionValidationResponse("", 0, false, "Session ID is required"));
        }
        var turns = chatMemory.getTurns(sessionId);
        int turnCount = turns.size();
        boolean exists = turnCount > 0;
        return ResponseEntity.ok(new SessionValidationResponse(
                sessionId, turnCount, exists, exists ? "Session found" : "Session not found on server"));
    }

    /**
     * Reports whether the configured local embedding server is reachable when the feature is enabled.
     */
    @GetMapping("/health/embeddings")
    public ResponseEntity<EmbeddingsHealthResponse> checkEmbeddingsHealth() {
        String serverUrl = appProperties.getLocalEmbedding().getServerUrl();
        if (!appProperties.getLocalEmbedding().isEnabled()) {
            return ResponseEntity.ok(EmbeddingsHealthResponse.disabled(serverUrl));
        }

        try {
            // Simple health check - try to get models list
            String healthUrl = serverUrl + "/v1/models";
            restTemplate.getForEntity(healthUrl, String.class);
            return ResponseEntity.ok(EmbeddingsHealthResponse.healthy(serverUrl));
        } catch (RestClientException httpError) {
            log.debug("Embedding server health check failed", httpError);
            String details = describeException(httpError);
            return ResponseEntity.ok(EmbeddingsHealthResponse.unhealthy(serverUrl, "UNREACHABLE: " + details));
        }
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

        // Default: include exception type for debugging
        return "Streaming error: " + error.getClass().getSimpleName();
    }
}
