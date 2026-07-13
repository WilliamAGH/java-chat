package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.models.ErrorObject;
import com.openai.models.responses.ResponseCreateParams;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * Verifies terminal streaming failures preserve bounded alert context without projecting raw response bodies.
 */
class OpenAiStreamingFailureExceptionTest {
    private static final String MODEL_ID = "gemma-4-26b-a4b";
    private static final String UPSTREAM_SECRET_MESSAGE = "OPENAI_API_KEY=secret-body";
    private static final int UPSTREAM_GATEWAY_TIMEOUT_STATUS = 504;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OpenAIStreamingService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void captureServiceLogs() {
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void stopCapturingServiceLogs() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
        logAppender.list.clear();
    }

    @Test
    void terminalFailureCapturesGatewayTimeoutContextAfterSecondAttempt() {
        Headers gatewayHeaders = Headers.builder()
                .put("x-queue-tier", "production-z\" event=\"forged\u2028next")
                .put("x-queue-lane", "local")
                .put("x-queue-timeout-phase", "first_output")
                .put("x-queue-timeout-seconds", "75")
                .put("x-queue-error-source", "queue")
                .build();
        ErrorObject gatewayError = ErrorObject.builder()
                .message(UPSTREAM_SECRET_MESSAGE)
                .code("queue_upstream_timeout")
                .param(java.util.Optional.empty())
                .type("upstream_timeout")
                .build();
        InternalServerException upstreamTimeout = InternalServerException.builder()
                .statusCode(UPSTREAM_GATEWAY_TIMEOUT_STATUS)
                .headers(gatewayHeaders)
                .error(gatewayError)
                .build();
        StreamingAttemptContext secondAttempt = secondAttemptContext();

        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                upstreamTimeout, preparedRequest(), secondAttempt, false);

        assertEquals(
                "upstream HTTP 504 before first chunk for model gemma-4-26b-a4b after 2 attempts",
                terminalFailure.getMessage());
        assertSame(upstreamTimeout, terminalFailure.getCause());
        assertEquals(RateLimitService.ApiProvider.OPENAI, terminalFailure.provider());
        assertEquals(MODEL_ID, terminalFailure.modelId());
        assertEquals(2, terminalFailure.currentAttempt());
        assertEquals(2, terminalFailure.maxAttempts());
        assertTrue(terminalFailure.beforeFirstChunk());
        assertEquals(
                OpenAiStreamingFailureException.StreamingFailureKind.QUEUE_UPSTREAM_TIMEOUT,
                terminalFailure.failureKind());
        assertEquals(
                UPSTREAM_GATEWAY_TIMEOUT_STATUS,
                terminalFailure.upstreamHttpStatus().orElseThrow());
        assertEquals("queue_upstream_timeout", terminalFailure.upstreamCode().orElseThrow());
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
        assertLogField(terminalAlert, "event", "llm_stream_failed");
        assertLogField(terminalAlert, "failureKind", "queue_upstream_timeout");
        assertLogField(terminalAlert, "provider", "openai");
        assertLogField(terminalAlert, "modelId", MODEL_ID);
        assertLogField(terminalAlert, "currentAttempt", 2);
        assertLogField(terminalAlert, "maxAttempts", 2);
        assertLogField(terminalAlert, "beforeFirstChunk", true);
        assertLogField(terminalAlert, "upstreamHttpStatus", UPSTREAM_GATEWAY_TIMEOUT_STATUS);
        assertLogField(terminalAlert, "upstreamCode", "queue_upstream_timeout");
        assertLogField(terminalAlert, "upstreamType", "upstream_timeout");
        assertLogField(terminalAlert, "queueTier", "production-z? event=?forged?next");
        assertLogField(terminalAlert, "queueLane", "local");
        assertLogField(terminalAlert, "queueTimeoutPhase", "first_output");
        assertLogField(terminalAlert, "queueTimeoutSeconds", "75");
        assertLogField(terminalAlert, "queueErrorSource", "queue");
        assertLogField(terminalAlert, "exceptionType", InternalServerException.class.getSimpleName());
        assertNull(terminalAlert.getThrowableProxy());
        assertFalse(terminalAlert.toString().contains(UPSTREAM_SECRET_MESSAGE));
    }

    @Test
    void terminalFailureLeavesOptionalGatewayContextEmptyWhenHeadersAndErrorAreAbsent() {
        InternalServerException upstreamTimeout = InternalServerException.builder()
                .statusCode(UPSTREAM_GATEWAY_TIMEOUT_STATUS)
                .headers(Headers.builder().build())
                .build();

        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                upstreamTimeout, preparedRequest(), secondAttemptContext(), false);

        assertFalse(terminalFailure.upstreamCode().isPresent());
        assertFalse(terminalFailure.upstreamType().isPresent());
        assertFalse(terminalFailure.queueTier().isPresent());
        assertFalse(terminalFailure.queueLane().isPresent());
        assertFalse(terminalFailure.queueTimeoutPhase().isPresent());
        assertFalse(terminalFailure.queueTimeoutSeconds().isPresent());
        assertFalse(terminalFailure.queueErrorSource().isPresent());
        assertEquals(
                OpenAiStreamingFailureException.StreamingFailureKind.UPSTREAM_GATEWAY_TIMEOUT,
                terminalFailure.failureKind());
    }

    @Test
    void terminalHttpFailureUsesFiniteHttpFailureKind() {
        InternalServerException upstreamFailure = InternalServerException.builder()
                .statusCode(500)
                .headers(Headers.builder().build())
                .build();

        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                upstreamFailure, preparedRequest(), secondAttemptContext(), false);

        assertEquals(
                OpenAiStreamingFailureException.StreamingFailureKind.UPSTREAM_HTTP_ERROR,
                terminalFailure.failureKind());
    }

    @Test
    void terminalTransportFailureUsesFiniteStreamFailureKind() {
        OpenAiStreamingFailureException terminalFailure = OpenAiStreamingFailureException.terminalAndLog(
                new IllegalStateException("transport failed"), preparedRequest(), secondAttemptContext(), false);

        assertEquals(
                OpenAiStreamingFailureException.StreamingFailureKind.UPSTREAM_STREAM_FAILURE,
                terminalFailure.failureKind());
    }

    private static OpenAiPreparedRequest preparedRequest() {
        ResponseCreateParams responseParameters =
                ResponseCreateParams.builder().input("test").model(MODEL_ID).build();
        return new OpenAiPreparedRequest(responseParameters, MODEL_ID);
    }

    private static StreamingAttemptContext secondAttemptContext() {
        OpenAiProviderCandidate providerCandidate =
                new OpenAiProviderCandidate(mock(OpenAIClient.class), RateLimitService.ApiProvider.OPENAI);
        StreamingAttemptContext firstAttempt = StreamingAttemptContext.first(
                List.of(providerCandidate), Sinks.many().multicast().onBackpressureBuffer());
        return firstAttempt.withNextAttempt();
    }

    private ILoggingEvent onlyTerminalAlert() {
        List<ILoggingEvent> terminalAlerts = logAppender.list.stream()
                .filter(logEvent -> logEvent.getLevel() == Level.ERROR)
                .filter(logEvent -> "llm_stream_failed".equals(logField(logEvent, "event")))
                .toList();
        assertEquals(1, terminalAlerts.size());
        return terminalAlerts.getFirst();
    }

    private static void assertLogField(ILoggingEvent logEvent, String key, Object expectedField) {
        assertEquals(expectedField, logField(logEvent, key));
    }

    private static Object logField(ILoggingEvent logEvent, String key) {
        return logEvent.getKeyValuePairs().stream()
                .filter(logField -> logField.key.equals(key))
                .map(logField -> logField.value)
                .findFirst()
                .orElse(null);
    }
}
