package com.williamcallahan.javachat.service;

import com.openai.errors.OpenAIServiceException;
import com.williamcallahan.javachat.support.StructuredLogValue;
import java.io.Serial;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Owns bounded terminal OpenAI stream context and its single structured failure alert.
 *
 * <p>The context intentionally projects only status, typed SDK error identifiers, and an allowlist of
 * queue headers. The exception retains the SDK cause for recoverability checks, but the structured alert
 * never attaches that cause or projects an upstream response body, arbitrary headers, or request identifiers.</p>
 */
public final class OpenAiStreamingFailureException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger STREAMING_LOG = LoggerFactory.getLogger(OpenAIStreamingService.class);
    private static final int MAX_STREAM_FAILURE_FIELD_LENGTH = 128;
    private static final String QUEUE_TIER_HEADER = "x-queue-tier";
    private static final String QUEUE_LANE_HEADER = "x-queue-lane";
    private static final String QUEUE_TIMEOUT_PHASE_HEADER = "x-queue-timeout-phase";
    private static final String QUEUE_TIMEOUT_SECONDS_HEADER = "x-queue-timeout-seconds";
    private static final String QUEUE_ERROR_SOURCE_HEADER = "x-queue-error-source";
    private static final String QUEUE_UPSTREAM_TIMEOUT_CODE = "queue_upstream_timeout";
    private static final int UPSTREAM_GATEWAY_TIMEOUT_STATUS = 504;

    enum StreamingFailureKind {
        QUEUE_UPSTREAM_TIMEOUT("queue_upstream_timeout"),
        UPSTREAM_GATEWAY_TIMEOUT("upstream_gateway_timeout"),
        UPSTREAM_HTTP_ERROR("upstream_http_error"),
        UPSTREAM_STREAM_FAILURE("upstream_stream_failure");

        private final String logName;

        StreamingFailureKind(String logName) {
            this.logName = logName;
        }

        String logName() {
            return logName;
        }
    }

    private final RateLimitService.ApiProvider provider;
    private final String modelId;
    private final int currentAttempt;
    private final int maxAttempts;
    private final boolean beforeFirstChunk;
    private final StreamingFailureKind failureKind;
    private final Integer upstreamHttpStatus;
    private final String upstreamCode;
    private final String upstreamType;
    private final String queueTier;
    private final String queueLane;
    private final String queueTimeoutPhase;
    private final String queueTimeoutSeconds;
    private final String queueErrorSource;

    private OpenAiStreamingFailureException(
            Throwable upstreamFailure,
            OpenAiPreparedRequest preparedRequest,
            StreamingAttemptContext attemptContext,
            boolean emittedTextChunk) {
        super(buildFailureMessage(upstreamFailure, preparedRequest, attemptContext, emittedTextChunk), upstreamFailure);
        Objects.requireNonNull(upstreamFailure, "upstreamFailure");
        Objects.requireNonNull(preparedRequest, "preparedRequest");
        Objects.requireNonNull(attemptContext, "attemptContext");
        this.provider = attemptContext.currentProvider().provider();
        this.modelId = boundedLogValue(preparedRequest.modelId());
        this.currentAttempt = attemptContext.currentAttempt();
        this.maxAttempts = attemptContext.maxAttempts();
        this.beforeFirstChunk = !emittedTextChunk;

        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            this.upstreamHttpStatus = serviceException.statusCode();
            this.upstreamCode = serviceException
                    .code()
                    .filter(errorCode -> !errorCode.isBlank())
                    .map(OpenAiStreamingFailureException::boundedLogValue)
                    .orElse(null);
            this.upstreamType = serviceException
                    .type()
                    .filter(errorType -> !errorType.isBlank())
                    .map(OpenAiStreamingFailureException::boundedLogValue)
                    .orElse(null);
            this.queueTier = boundedHeader(serviceException, QUEUE_TIER_HEADER);
            this.queueLane = boundedHeader(serviceException, QUEUE_LANE_HEADER);
            this.queueTimeoutPhase = boundedHeader(serviceException, QUEUE_TIMEOUT_PHASE_HEADER);
            this.queueTimeoutSeconds = boundedHeader(serviceException, QUEUE_TIMEOUT_SECONDS_HEADER);
            this.queueErrorSource = boundedHeader(serviceException, QUEUE_ERROR_SOURCE_HEADER);
        } else {
            this.upstreamHttpStatus = null;
            this.upstreamCode = null;
            this.upstreamType = null;
            this.queueTier = null;
            this.queueLane = null;
            this.queueTimeoutPhase = null;
            this.queueTimeoutSeconds = null;
            this.queueErrorSource = null;
        }
        this.failureKind = determineFailureKind(upstreamFailure, upstreamCode);
    }

    static OpenAiStreamingFailureException terminalAndLog(
            Throwable upstreamFailure,
            OpenAiPreparedRequest preparedRequest,
            StreamingAttemptContext attemptContext,
            boolean emittedTextChunk) {
        OpenAiStreamingFailureException terminalFailure =
                new OpenAiStreamingFailureException(upstreamFailure, preparedRequest, attemptContext, emittedTextChunk);
        terminalFailure.logTerminalAlert();
        return terminalFailure;
    }

    /**
     * Finds an already-logged terminal streaming failure anywhere in an exception cause chain.
     *
     * @param failure exception received by an outer request boundary
     * @return the terminal streaming failure when the service already emitted its structured alert
     */
    public static Optional<OpenAiStreamingFailureException> findInCauseChain(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Set<Throwable> inspectedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable currentFailure = failure;
        while (currentFailure != null && inspectedFailures.add(currentFailure)) {
            if (currentFailure instanceof OpenAiStreamingFailureException terminalFailure) {
                return Optional.of(terminalFailure);
            }
            currentFailure = currentFailure.getCause();
        }
        return Optional.empty();
    }

    /** Returns the provider used by the terminal attempt. */
    public RateLimitService.ApiProvider provider() {
        return provider;
    }

    /** Returns the bounded model identifier used by the terminal attempt. */
    public String modelId() {
        return modelId;
    }

    /** Returns the one-based terminal attempt number. */
    public int currentAttempt() {
        return currentAttempt;
    }

    /** Returns the maximum attempts available to this stream. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns whether the terminal failure occurred before any text chunk was emitted. */
    public boolean beforeFirstChunk() {
        return beforeFirstChunk;
    }

    /** Returns the finite failure category used for alert grouping and rendering. */
    StreamingFailureKind failureKind() {
        return failureKind;
    }

    /** Returns the upstream HTTP status when the SDK supplied one. */
    public OptionalInt upstreamHttpStatus() {
        return upstreamHttpStatus == null ? OptionalInt.empty() : OptionalInt.of(upstreamHttpStatus);
    }

    /** Returns the bounded OpenAI-compatible error code when supplied. */
    public Optional<String> upstreamCode() {
        return Optional.ofNullable(upstreamCode);
    }

    /** Returns the bounded OpenAI-compatible error type when supplied. */
    public Optional<String> upstreamType() {
        return Optional.ofNullable(upstreamType);
    }

    /** Returns the bounded queue tier header when supplied. */
    public Optional<String> queueTier() {
        return Optional.ofNullable(queueTier);
    }

    /** Returns the bounded queue lane header when supplied. */
    public Optional<String> queueLane() {
        return Optional.ofNullable(queueLane);
    }

    /** Returns the bounded queue timeout phase header when supplied. */
    public Optional<String> queueTimeoutPhase() {
        return Optional.ofNullable(queueTimeoutPhase);
    }

    /** Returns the bounded queue timeout seconds header when supplied. */
    public Optional<String> queueTimeoutSeconds() {
        return Optional.ofNullable(queueTimeoutSeconds);
    }

    /** Returns the bounded queue error source header when supplied. */
    public Optional<String> queueErrorSource() {
        return Optional.ofNullable(queueErrorSource);
    }

    private void logTerminalAlert() {
        LoggingEventBuilder terminalAlert = STREAMING_LOG
                .atError()
                .setMessage(getMessage())
                .addKeyValue("event", "llm_stream_failed")
                .addKeyValue("provider", provider.getName())
                .addKeyValue("modelId", modelId)
                .addKeyValue("currentAttempt", currentAttempt)
                .addKeyValue("maxAttempts", maxAttempts)
                .addKeyValue("beforeFirstChunk", beforeFirstChunk)
                .addKeyValue("failureKind", failureKind.logName())
                .addKeyValue("exceptionType", getCause().getClass().getSimpleName());
        if (upstreamHttpStatus != null) {
            terminalAlert = terminalAlert.addKeyValue("upstreamHttpStatus", upstreamHttpStatus);
        }
        if (upstreamCode != null) {
            terminalAlert = terminalAlert.addKeyValue("upstreamCode", upstreamCode);
        }
        if (upstreamType != null) {
            terminalAlert = terminalAlert.addKeyValue("upstreamType", upstreamType);
        }
        if (queueTier != null) {
            terminalAlert = terminalAlert.addKeyValue("queueTier", queueTier);
        }
        if (queueLane != null) {
            terminalAlert = terminalAlert.addKeyValue("queueLane", queueLane);
        }
        if (queueTimeoutPhase != null) {
            terminalAlert = terminalAlert.addKeyValue("queueTimeoutPhase", queueTimeoutPhase);
        }
        if (queueTimeoutSeconds != null) {
            terminalAlert = terminalAlert.addKeyValue("queueTimeoutSeconds", queueTimeoutSeconds);
        }
        if (queueErrorSource != null) {
            terminalAlert = terminalAlert.addKeyValue("queueErrorSource", queueErrorSource);
        }
        terminalAlert.log();
    }

    private static StreamingFailureKind determineFailureKind(Throwable upstreamFailure, String upstreamCode) {
        if (QUEUE_UPSTREAM_TIMEOUT_CODE.equals(upstreamCode)) {
            return StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT;
        }
        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() == UPSTREAM_GATEWAY_TIMEOUT_STATUS
                    ? StreamingFailureKind.UPSTREAM_GATEWAY_TIMEOUT
                    : StreamingFailureKind.UPSTREAM_HTTP_ERROR;
        }
        return StreamingFailureKind.UPSTREAM_STREAM_FAILURE;
    }

    private static String buildFailureMessage(
            Throwable upstreamFailure,
            OpenAiPreparedRequest preparedRequest,
            StreamingAttemptContext attemptContext,
            boolean emittedTextChunk) {
        Objects.requireNonNull(upstreamFailure, "upstreamFailure");
        Objects.requireNonNull(preparedRequest, "preparedRequest");
        Objects.requireNonNull(attemptContext, "attemptContext");
        String streamPhase = emittedTextChunk ? "after first chunk" : "before first chunk";
        String attemptUnit = attemptContext.currentAttempt() == 1 ? "attempt" : "attempts";
        String boundedModelId = boundedLogValue(preparedRequest.modelId());
        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            return "upstream HTTP " + serviceException.statusCode() + " " + streamPhase + " for model " + boundedModelId
                    + " after " + attemptContext.currentAttempt() + " " + attemptUnit;
        }
        return "upstream stream failure " + streamPhase + " for model " + boundedModelId + " after "
                + attemptContext.currentAttempt() + " " + attemptUnit;
    }

    private static String boundedHeader(OpenAIServiceException serviceException, String headerName) {
        List<String> headerValues = serviceException.headers().values(headerName);
        if (headerValues.size() != 1) {
            return null;
        }
        String headerText = headerValues.getFirst();
        return headerText == null || headerText.isBlank() ? null : boundedLogValue(headerText);
    }

    private static String boundedLogValue(String fieldText) {
        return StructuredLogValue.bounded(
                        Objects.requireNonNull(fieldText, "fieldText"), MAX_STREAM_FAILURE_FIELD_LENGTH)
                .text();
    }
}
