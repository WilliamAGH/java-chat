package com.williamcallahan.javachat.adapters.out.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.models.ErrorObject;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException.AlertField;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException.QueueHeader;
import com.williamcallahan.javachat.adapters.out.llm.openai.OpenAiStreamingFailureException.StreamingFailureKind;
import com.williamcallahan.javachat.application.streaming.ReportedStreamingFailure;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter.TerminalAttempt;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies terminal OpenAI-compatible failures preserve bounded alert context without projecting raw bodies.
 */
class OpenAiStreamingFailureExceptionTest {
    private static final String MODEL_ID = "gemma-4-26b-a4b";
    private static final String PROVIDER_NAME = "openai";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";
    private static final int UPSTREAM_GATEWAY_TIMEOUT_STATUS = 504;

    private final Logger adapterLogger = (Logger) LoggerFactory.getLogger(OpenAiStreamingFailureException.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void captureAdapterLogs() {
        logAppender.start();
        adapterLogger.addAppender(logAppender);
    }

    @AfterEach
    void stopCapturingAdapterLogs() {
        adapterLogger.detachAppender(logAppender);
        logAppender.stop();
        logAppender.list.clear();
    }

    @Test
    void terminalFailureCapturesGatewayTimeoutContextAfterSecondAttempt() {
        Headers gatewayHeaders = Headers.builder()
                .put(QueueHeader.TIER.headerName(), "production-z\" event=\"forged\u2028next")
                .put(QueueHeader.LANE.headerName(), "local")
                .put(QueueHeader.TIMEOUT_PHASE.headerName(), "first_output")
                .put(QueueHeader.TIMEOUT_SECONDS.headerName(), "75")
                .put(QueueHeader.ERROR_SOURCE.headerName(), "queue")
                .build();
        ErrorObject gatewayError = ErrorObject.builder()
                .message(UPSTREAM_SECRET_MESSAGE)
                .code(StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT.wireName())
                .param(java.util.Optional.empty())
                .type("upstream_timeout")
                .build();
        InternalServerException upstreamTimeout = InternalServerException.builder()
                .statusCode(UPSTREAM_GATEWAY_TIMEOUT_STATUS)
                .headers(gatewayHeaders)
                .error(gatewayError)
                .build();

        OpenAiStreamingFailureException terminalFailure =
                OpenAiStreamingFailureException.terminalAndLog(upstreamTimeout, terminalAttempt(false));

        assertEquals(
                "upstream HTTP 504 before first chunk for model gemma-4-26b-a4b after 2 attempts",
                terminalFailure.getMessage());
        assertSame(upstreamTimeout, terminalFailure.upstreamFailure());
        assertEquals(PROVIDER_NAME, terminalFailure.providerName());
        assertEquals(MODEL_ID, terminalFailure.modelId());
        assertEquals(2, terminalFailure.currentAttempt());
        assertEquals(2, terminalFailure.maxAttempts());
        assertTrue(terminalFailure.beforeFirstChunk());
        assertEquals(StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT, terminalFailure.failureKind());
        assertEquals(
                UPSTREAM_GATEWAY_TIMEOUT_STATUS,
                terminalFailure.upstreamHttpStatus().orElseThrow());
        assertEquals(
                StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT.wireName(),
                terminalFailure.upstreamCode().orElseThrow());
        assertEquals("upstream_timeout", terminalFailure.upstreamType().orElseThrow());
        assertEquals(
                "production-z? event=?forged?next", terminalFailure.queueTier().orElseThrow());
        assertEquals("local", terminalFailure.queueLane().orElseThrow());
        assertEquals("first_output", terminalFailure.queueTimeoutPhase().orElseThrow());
        assertEquals("75", terminalFailure.queueTimeoutSeconds().orElseThrow());
        assertEquals("queue", terminalFailure.queueErrorSource().orElseThrow());

        ILoggingEvent terminalAlert = onlyTerminalAlert();
        assertEquals(
                "upstream HTTP 504 before first chunk for model gemma-4-26b-a4b after 2 attempts",
                terminalAlert.getFormattedMessage());
        assertLogField(
                terminalAlert, AlertField.EVENT, OpenAiStreamingFailureException.TERMINAL_STREAM_FAILURE_EVENT_NAME);
        assertLogField(terminalAlert, AlertField.FAILURE_KIND, StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT.wireName());
        assertLogField(terminalAlert, AlertField.PROVIDER, PROVIDER_NAME);
        assertLogField(terminalAlert, AlertField.MODEL_ID, MODEL_ID);
        assertLogField(terminalAlert, AlertField.CURRENT_ATTEMPT, 2);
        assertLogField(terminalAlert, AlertField.MAX_ATTEMPTS, 2);
        assertLogField(terminalAlert, AlertField.BEFORE_FIRST_CHUNK, true);
        assertLogField(terminalAlert, AlertField.UPSTREAM_HTTP_STATUS, UPSTREAM_GATEWAY_TIMEOUT_STATUS);
        assertLogField(terminalAlert, AlertField.UPSTREAM_CODE, StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT.wireName());
        assertLogField(terminalAlert, AlertField.UPSTREAM_TYPE, "upstream_timeout");
        assertLogField(terminalAlert, QueueHeader.TIER.alertField(), "production-z? event=?forged?next");
        assertLogField(terminalAlert, QueueHeader.LANE.alertField(), "local");
        assertLogField(terminalAlert, QueueHeader.TIMEOUT_PHASE.alertField(), "first_output");
        assertLogField(terminalAlert, QueueHeader.TIMEOUT_SECONDS.alertField(), "75");
        assertLogField(terminalAlert, QueueHeader.ERROR_SOURCE.alertField(), "queue");
        assertLogField(terminalAlert, AlertField.EXCEPTION_TYPE, InternalServerException.class.getSimpleName());
        assertNull(terminalAlert.getThrowableProxy());
        assertFalse(terminalAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
    }

    @Test
    void terminalFailureLeavesOptionalGatewayContextEmptyWhenHeadersAndErrorAreAbsent() {
        InternalServerException upstreamTimeout = InternalServerException.builder()
                .statusCode(UPSTREAM_GATEWAY_TIMEOUT_STATUS)
                .headers(Headers.builder().build())
                .build();

        OpenAiStreamingFailureException terminalFailure =
                OpenAiStreamingFailureException.terminalAndLog(upstreamTimeout, terminalAttempt(false));

        assertFalse(terminalFailure.upstreamCode().isPresent());
        assertFalse(terminalFailure.upstreamType().isPresent());
        assertFalse(terminalFailure.queueTier().isPresent());
        assertFalse(terminalFailure.queueLane().isPresent());
        assertFalse(terminalFailure.queueTimeoutPhase().isPresent());
        assertFalse(terminalFailure.queueTimeoutSeconds().isPresent());
        assertFalse(terminalFailure.queueErrorSource().isPresent());
        assertEquals(StreamingFailureKind.UPSTREAM_GATEWAY_TIMEOUT, terminalFailure.failureKind());
    }

    @Test
    void terminalHttpFailureUsesFiniteHttpFailureKind() {
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(500)
                .headers(Headers.builder().build())
                .build();

        OpenAiStreamingFailureException terminalFailure =
                OpenAiStreamingFailureException.terminalAndLog(upstreamFailure, terminalAttempt(false));

        assertEquals(StreamingFailureKind.UPSTREAM_HTTP_ERROR, terminalFailure.failureKind());
    }

    @Test
    void terminalTransportFailureUsesFiniteStreamFailureKind() {
        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                new IllegalStateException("transport failed"), terminalAttempt(false));

        assertEquals(StreamingFailureKind.UPSTREAM_STREAM_FAILURE, terminalFailure.failureKind());
    }

    @Test
    void reportedFailureFindsTerminalFailureThroughNestedCauseChain() {
        IllegalStateException upstreamFailure = new IllegalStateException("transport failed");
        OpenAiStreamingFailureException terminalFailure =
                OpenAiStreamingFailureException.terminalAndLog(upstreamFailure, terminalAttempt(false));
        IllegalStateException requestBoundaryFailure = new IllegalStateException("request boundary", terminalFailure);

        ReportedStreamingFailure reportedStreamingFailure = ReportedStreamingFailure.findInCauseChain(
                        requestBoundaryFailure)
                .orElseThrow();

        assertSame(terminalFailure, reportedStreamingFailure);
        assertSame(upstreamFailure, reportedStreamingFailure.upstreamFailure());
    }

    @Test
    void reportedFailureTraversalStopsAtIdentityCycleWithoutMarker() {
        IllegalStateException firstFailure = new IllegalStateException("first failure");
        IllegalStateException secondFailure = new IllegalStateException("second failure", firstFailure);
        firstFailure.initCause(secondFailure);

        assertFalse(ReportedStreamingFailure.findInCauseChain(firstFailure).isPresent());
    }

    @Test
    void terminalAttemptRejectsInvalidProviderModelAndAttemptBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalAttempt(PROVIDER_NAME, MODEL_ID, 0, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TerminalAttempt(PROVIDER_NAME, MODEL_ID, 2, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TerminalAttempt(" ", MODEL_ID, 1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TerminalAttempt(PROVIDER_NAME, "", 1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new TerminalAttempt(PROVIDER_NAME, MODEL_ID, 1, 0, false));
    }

    private static TerminalAttempt terminalAttempt(boolean emittedTextChunk) {
        return new TerminalAttempt(PROVIDER_NAME, MODEL_ID, 2, 2, emittedTextChunk);
    }

    private ILoggingEvent onlyTerminalAlert() {
        List<ILoggingEvent> terminalAlerts = logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .filter(logEvent -> OpenAiStreamingFailureException.TERMINAL_STREAM_FAILURE_EVENT_NAME.equals(
                        logField(logEvent, AlertField.EVENT)))
                .toList();
        assertEquals(1, terminalAlerts.size());
        return terminalAlerts.getFirst();
    }

    private static void assertLogField(ILoggingEvent loggingEvent, AlertField alertField, Object expectedField) {
        assertEquals(expectedField, logField(loggingEvent, alertField));
    }

    private static Object logField(ILoggingEvent loggingEvent, AlertField alertField) {
        return loggingEvent.getKeyValuePairs().stream()
                .filter(structuredField -> structuredField.key.equals(alertField.fieldName()))
                .map(structuredField -> structuredField.value)
                .findFirst()
                .orElse(null);
    }
}
