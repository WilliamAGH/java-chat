package com.williamcallahan.javachat.adapters.out.llm.openai;

import com.openai.errors.OpenAIServiceException;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter.TerminalAttempt;
import com.williamcallahan.javachat.support.StructuredLogValue;
import java.io.Serial;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Captures bounded terminal context from an OpenAI-compatible streaming provider.
 *
 * <p>The exception retains the upstream cause for recovery decisions but emits exactly one structured alert
 * without projecting upstream bodies, arbitrary headers, or request identifiers.</p>
 */
public final class OpenAiStreamingFailureException extends RuntimeException implements ReportedStreamingFailure {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamingFailureException.class);
    private static final int MAX_STREAM_FAILURE_FIELD_LENGTH = 128;
    private static final int UPSTREAM_GATEWAY_TIMEOUT_STATUS = 504;
    static final String TERMINAL_STREAM_FAILURE_EVENT_NAME = "llm_stream_failed";

    /** Owns every key emitted in the terminal streaming-failure alert. */
    enum AlertField {
        EVENT("event"),
        PROVIDER("provider"),
        MODEL_ID("modelId"),
        CURRENT_ATTEMPT("currentAttempt"),
        MAX_ATTEMPTS("maxAttempts"),
        BEFORE_FIRST_CHUNK("beforeFirstChunk"),
        FAILURE_KIND("failureKind"),
        EXCEPTION_TYPE("exceptionType"),
        UPSTREAM_HTTP_STATUS("upstreamHttpStatus"),
        UPSTREAM_CODE("upstreamCode"),
        UPSTREAM_TYPE("upstreamType"),
        QUEUE_TIER("queueTier"),
        QUEUE_LANE("queueLane"),
        QUEUE_TIMEOUT_PHASE("queueTimeoutPhase"),
        QUEUE_TIMEOUT_SECONDS("queueTimeoutSeconds"),
        QUEUE_ERROR_SOURCE("queueErrorSource");

        private final String fieldName;

        AlertField(String fieldName) {
            this.fieldName = fieldName;
        }

        String fieldName() {
            return fieldName;
        }
    }

    /** Owns the queue headers that may be projected into their paired alert fields. */
    enum QueueHeader {
        TIER("x-queue-tier", AlertField.QUEUE_TIER),
        LANE("x-queue-lane", AlertField.QUEUE_LANE),
        TIMEOUT_PHASE("x-queue-timeout-phase", AlertField.QUEUE_TIMEOUT_PHASE),
        TIMEOUT_SECONDS("x-queue-timeout-seconds", AlertField.QUEUE_TIMEOUT_SECONDS),
        ERROR_SOURCE("x-queue-error-source", AlertField.QUEUE_ERROR_SOURCE);

        private final String headerName;
        private final AlertField alertField;

        QueueHeader(String headerName, AlertField alertField) {
            this.headerName = headerName;
            this.alertField = alertField;
        }

        String headerName() {
            return headerName;
        }

        AlertField alertField() {
            return alertField;
        }
    }

    /** Classifies terminal failures without exposing upstream exception text. */
    enum StreamingFailureKind {
        QUEUE_UPSTREAM_TIMEOUT("queue_upstream_timeout"),
        UPSTREAM_GATEWAY_TIMEOUT("upstream_gateway_timeout"),
        UPSTREAM_HTTP_ERROR("upstream_http_error"),
        UPSTREAM_STREAM_FAILURE("upstream_stream_failure");

        private final String wireName;

        StreamingFailureKind(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private final String providerName;
    private final String modelId;
    private final int currentAttempt;
    private final int maxAttempts;
    private final boolean emittedTextChunk;
    private final StreamingFailureKind failureKind;
    private final Integer upstreamHttpStatus;
    private final String upstreamCode;
    private final String upstreamType;
    private final Map<QueueHeader, String> queueHeaderValues;

    private OpenAiStreamingFailureException(Throwable upstreamFailure, TerminalAttempt terminalAttempt) {
        super(
                buildFailureMessage(upstreamFailure, terminalAttempt),
                Objects.requireNonNull(upstreamFailure, "upstreamFailure"));
        TerminalAttempt validatedAttempt = Objects.requireNonNull(terminalAttempt, "terminalAttempt");
        this.providerName = boundedLogValue(validatedAttempt.providerName());
        this.modelId = boundedLogValue(validatedAttempt.modelId());
        this.currentAttempt = validatedAttempt.currentAttempt();
        this.maxAttempts = validatedAttempt.maxAttempts();
        this.emittedTextChunk = validatedAttempt.emittedTextChunk();
        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            this.upstreamHttpStatus = serviceException.statusCode();
            this.upstreamCode = boundedOptionalValue(serviceException.code()).orElse(null);
            this.upstreamType = boundedOptionalValue(serviceException.type()).orElse(null);
            this.queueHeaderValues = captureQueueHeaderValues(serviceException);
        } else {
            this.upstreamHttpStatus = null;
            this.upstreamCode = null;
            this.upstreamType = null;
            this.queueHeaderValues = Map.of();
        }
        this.failureKind = determineFailureKind(upstreamFailure, Optional.ofNullable(upstreamCode));
    }

    /**
     * Creates and reports one terminal OpenAI-compatible streaming failure.
     *
     * @param upstreamFailure failure returned by the provider transport
     * @param terminalAttempt immutable context of the terminal provider attempt
     * @return reported terminal failure that preserves the upstream cause
     */
    public static OpenAiStreamingFailureException terminalAndLog(
            Throwable upstreamFailure, TerminalAttempt terminalAttempt) {
        OpenAiStreamingFailureException terminalFailure =
                new OpenAiStreamingFailureException(upstreamFailure, terminalAttempt);
        terminalFailure.logTerminalAlert();
        return terminalFailure;
    }

    /** Returns the upstream failure that the provider boundary reported. */
    @Override
    public Throwable upstreamFailure() {
        return Objects.requireNonNull(getCause(), "upstreamFailure");
    }

    /** Returns the bounded provider name used by the terminal attempt. */
    String providerName() {
        return providerName;
    }

    /** Returns the bounded model identifier used by the terminal attempt. */
    String modelId() {
        return modelId;
    }

    /** Returns the one-based terminal attempt number. */
    int currentAttempt() {
        return currentAttempt;
    }

    /** Returns the maximum attempts available to the stream. */
    int maxAttempts() {
        return maxAttempts;
    }

    /** Returns whether the terminal failure occurred before any text chunk was emitted. */
    boolean beforeFirstChunk() {
        return !emittedTextChunk;
    }

    /** Returns the finite failure category used for alert grouping and rendering. */
    StreamingFailureKind failureKind() {
        return failureKind;
    }

    /** Returns the upstream HTTP status when the SDK supplied one. */
    OptionalInt upstreamHttpStatus() {
        return upstreamHttpStatus == null ? OptionalInt.empty() : OptionalInt.of(upstreamHttpStatus);
    }

    /** Returns the bounded OpenAI-compatible error code when the SDK supplied one. */
    Optional<String> upstreamCode() {
        return Optional.ofNullable(upstreamCode);
    }

    /** Returns the bounded OpenAI-compatible error type when the SDK supplied one. */
    Optional<String> upstreamType() {
        return Optional.ofNullable(upstreamType);
    }

    /** Returns the bounded queue tier header when the provider supplied one. */
    Optional<String> queueTier() {
        return queueHeaderValue(QueueHeader.TIER);
    }

    /** Returns the bounded queue lane header when the provider supplied one. */
    Optional<String> queueLane() {
        return queueHeaderValue(QueueHeader.LANE);
    }

    /** Returns the bounded queue timeout phase header when the provider supplied one. */
    Optional<String> queueTimeoutPhase() {
        return queueHeaderValue(QueueHeader.TIMEOUT_PHASE);
    }

    /** Returns the bounded queue timeout seconds header when the provider supplied one. */
    Optional<String> queueTimeoutSeconds() {
        return queueHeaderValue(QueueHeader.TIMEOUT_SECONDS);
    }

    /** Returns the bounded queue error source header when the provider supplied one. */
    Optional<String> queueErrorSource() {
        return queueHeaderValue(QueueHeader.ERROR_SOURCE);
    }

    private void logTerminalAlert() {
        LoggingEventBuilder terminalAlert = log.atError()
                .setMessage(getMessage())
                .addKeyValue(AlertField.EVENT.fieldName(), TERMINAL_STREAM_FAILURE_EVENT_NAME)
                .addKeyValue(AlertField.PROVIDER.fieldName(), providerName())
                .addKeyValue(AlertField.MODEL_ID.fieldName(), modelId())
                .addKeyValue(AlertField.CURRENT_ATTEMPT.fieldName(), currentAttempt())
                .addKeyValue(AlertField.MAX_ATTEMPTS.fieldName(), maxAttempts())
                .addKeyValue(AlertField.BEFORE_FIRST_CHUNK.fieldName(), beforeFirstChunk())
                .addKeyValue(AlertField.FAILURE_KIND.fieldName(), failureKind.wireName())
                .addKeyValue(
                        AlertField.EXCEPTION_TYPE.fieldName(),
                        upstreamFailure().getClass().getSimpleName());
        if (upstreamHttpStatus != null) {
            terminalAlert = terminalAlert.addKeyValue(AlertField.UPSTREAM_HTTP_STATUS.fieldName(), upstreamHttpStatus);
        }
        if (upstreamCode != null) {
            terminalAlert = terminalAlert.addKeyValue(AlertField.UPSTREAM_CODE.fieldName(), upstreamCode);
        }
        if (upstreamType != null) {
            terminalAlert = terminalAlert.addKeyValue(AlertField.UPSTREAM_TYPE.fieldName(), upstreamType);
        }
        for (QueueHeader queueHeader : QueueHeader.values()) {
            if (queueHeaderValues.containsKey(queueHeader)) {
                terminalAlert = terminalAlert.addKeyValue(
                        queueHeader.alertField().fieldName(), queueHeaderValues.get(queueHeader));
            }
        }
        terminalAlert.log();
    }

    private static StreamingFailureKind determineFailureKind(Throwable upstreamFailure, Optional<String> upstreamCode) {
        if (upstreamCode
                .filter(StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT.wireName()::equals)
                .isPresent()) {
            return StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT;
        }
        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            return serviceException.statusCode() == UPSTREAM_GATEWAY_TIMEOUT_STATUS
                    ? StreamingFailureKind.UPSTREAM_GATEWAY_TIMEOUT
                    : StreamingFailureKind.UPSTREAM_HTTP_ERROR;
        }
        return StreamingFailureKind.UPSTREAM_STREAM_FAILURE;
    }

    private static String buildFailureMessage(Throwable upstreamFailure, TerminalAttempt terminalAttempt) {
        Objects.requireNonNull(upstreamFailure, "upstreamFailure");
        Objects.requireNonNull(terminalAttempt, "terminalAttempt");
        String streamPhase = terminalAttempt.emittedTextChunk() ? "after first chunk" : "before first chunk";
        int currentAttempt = terminalAttempt.currentAttempt();
        String attemptUnit = currentAttempt == 1 ? "attempt" : "attempts";
        String boundedModelId = boundedLogValue(terminalAttempt.modelId());
        if (upstreamFailure instanceof OpenAIServiceException serviceException) {
            return "upstream HTTP " + serviceException.statusCode() + " " + streamPhase + " for model " + boundedModelId
                    + " after " + currentAttempt + " " + attemptUnit;
        }
        return "upstream stream failure " + streamPhase + " for model " + boundedModelId + " after " + currentAttempt
                + " " + attemptUnit;
    }

    private static Optional<String> boundedOptionalValue(Optional<String> optionalFieldValue) {
        return Objects.requireNonNull(optionalFieldValue, "optionalFieldValue")
                .filter(fieldValue -> !fieldValue.isBlank())
                .map(OpenAiStreamingFailureException::boundedLogValue);
    }

    private static Map<QueueHeader, String> captureQueueHeaderValues(OpenAIServiceException serviceException) {
        EnumMap<QueueHeader, String> queueValues = new EnumMap<>(QueueHeader.class);
        for (QueueHeader queueHeader : QueueHeader.values()) {
            boundedHeader(serviceException, queueHeader)
                    .ifPresent(queueValue -> queueValues.put(queueHeader, queueValue));
        }
        return Map.copyOf(queueValues);
    }

    private static Optional<String> boundedHeader(OpenAIServiceException serviceException, QueueHeader queueHeader) {
        Objects.requireNonNull(serviceException, "serviceException");
        Objects.requireNonNull(queueHeader, "queueHeader");
        List<String> headerValues = serviceException.headers().values(queueHeader.headerName());
        if (headerValues.size() != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(headerValues.getFirst())
                .filter(headerValue -> !headerValue.isBlank())
                .map(OpenAiStreamingFailureException::boundedLogValue);
    }

    private Optional<String> queueHeaderValue(QueueHeader queueHeader) {
        return Optional.ofNullable(queueHeaderValues.get(Objects.requireNonNull(queueHeader, "queueHeader")));
    }

    private static String boundedLogValue(String fieldText) {
        return StructuredLogValue.bounded(
                        Objects.requireNonNull(fieldText, "fieldText"), MAX_STREAM_FAILURE_FIELD_LENGTH)
                .text();
    }
}
