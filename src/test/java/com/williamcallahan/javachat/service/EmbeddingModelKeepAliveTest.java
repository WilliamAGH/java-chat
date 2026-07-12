package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Status;

/**
 * Verifies that embedding probes expose readiness and classify lifecycle events without warning storms.
 */
class EmbeddingModelKeepAliveTest {
    private final Logger lifecycleLogger = (Logger) LoggerFactory.getLogger(EmbeddingModelKeepAlive.class);
    private final ListAppender<ILoggingEvent> lifecycleEvents = new ListAppender<>();
    private boolean lifecycleLoggerAdditive;

    @BeforeEach
    void captureLifecycleEvents() {
        lifecycleLoggerAdditive = lifecycleLogger.isAdditive();
        lifecycleLogger.setAdditive(false);
        lifecycleEvents.start();
        lifecycleLogger.addAppender(lifecycleEvents);
    }

    @AfterEach
    void stopCapturingLifecycleEvents() {
        lifecycleLogger.detachAppender(lifecycleEvents);
        lifecycleLogger.setAdditive(lifecycleLoggerAdditive);
        lifecycleEvents.stop();
    }

    @Test
    void successfulProbeMakesEmbeddingHealthReady() {
        SequencedEmbeddingClient embeddingClient = new SequencedEmbeddingClient(ProbeOutcome.SUCCESS);
        EmbeddingModelKeepAlive keepAlive = new EmbeddingModelKeepAlive(embeddingClient, new SequencedNanoTime(100));

        assertEquals(Status.DOWN, keepAlive.health().getStatus());

        keepAlive.keepEmbeddingModelWarm();

        assertEquals(1, embeddingClient.warmUpInvocationCount);
        assertEquals(Status.UP, keepAlive.health().getStatus());
        assertEquals("test-embedding-model", keepAlive.health().getDetails().get("model"));
        assertEquals(1, eventCount(Level.INFO, "event=embedding_model_probe_ready"));

        keepAlive.retryUnavailableEmbeddingModel();

        assertEquals(1, embeddingClient.warmUpInvocationCount);
    }

    @Test
    void repeatedSlowProbesWarnOnceAndRecoveryIsRecorded() {
        SequencedEmbeddingClient embeddingClient = new SequencedEmbeddingClient(
                ProbeOutcome.SUCCESS, ProbeOutcome.SUCCESS, ProbeOutcome.SUCCESS, ProbeOutcome.SUCCESS);
        EmbeddingModelKeepAlive keepAlive =
                new EmbeddingModelKeepAlive(embeddingClient, new SequencedNanoTime(6_001, 6_002, 6_003, 100));

        keepAlive.keepEmbeddingModelWarm();
        keepAlive.keepEmbeddingModelWarm();
        keepAlive.keepEmbeddingModelWarm();
        keepAlive.keepEmbeddingModelWarm();

        assertEquals(Status.UP, keepAlive.health().getStatus());
        assertEquals(1, eventCount(Level.INFO, "event=embedding_model_probe_slow"));
        assertEquals(1, eventCount(Level.WARN, "event=embedding_model_probe_slow_loop"));
        assertEquals(1, eventCount(Level.INFO, "event=embedding_model_probe_recovered"));
        assertTrue(lifecycleEvents.list.stream()
                .noneMatch(loggingEvent -> loggingEvent.getFormattedMessage().contains("cold and has been reloaded")));
    }

    @Test
    void repeatedFailuresEscalateOnceAndRecoveryRestoresHealth() {
        SequencedEmbeddingClient embeddingClient = new SequencedEmbeddingClient(
                ProbeOutcome.UNAVAILABLE, ProbeOutcome.UNAVAILABLE, ProbeOutcome.UNAVAILABLE, ProbeOutcome.SUCCESS);
        EmbeddingModelKeepAlive keepAlive =
                new EmbeddingModelKeepAlive(embeddingClient, new SequencedNanoTime(10, 20, 30, 6_000));

        keepAlive.keepEmbeddingModelWarm();
        keepAlive.keepEmbeddingModelWarm();
        keepAlive.keepEmbeddingModelWarm();

        assertEquals(Status.DOWN, keepAlive.health().getStatus());
        assertEquals(1, eventCount(Level.WARN, "event=embedding_model_probe_failed"));
        assertEquals(1, eventCount(Level.ERROR, "event=embedding_model_probe_failure_loop"));

        keepAlive.retryUnavailableEmbeddingModel();

        assertEquals(Status.UP, keepAlive.health().getStatus());
        assertEquals(1, eventCount(Level.INFO, "event=embedding_model_probe_recovered"));
    }

    @Test
    void unexpectedProbeFailureMarksHealthDownAndPropagates() {
        SequencedEmbeddingClient embeddingClient =
                new SequencedEmbeddingClient(ProbeOutcome.SUCCESS, ProbeOutcome.UNEXPECTED);
        EmbeddingModelKeepAlive keepAlive =
                new EmbeddingModelKeepAlive(embeddingClient, new SequencedNanoTime(100, 10));

        keepAlive.keepEmbeddingModelWarm();

        assertEquals(Status.UP, keepAlive.health().getStatus());

        assertThrows(IllegalStateException.class, keepAlive::keepEmbeddingModelWarm);

        assertEquals(Status.DOWN, keepAlive.health().getStatus());
        assertEquals(1, eventCount(Level.WARN, "event=embedding_model_probe_failed"));
    }

    private long eventCount(Level level, String eventName) {
        return lifecycleEvents.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel().equals(level))
                .filter(loggingEvent -> loggingEvent.getFormattedMessage().contains(eventName))
                .count();
    }

    /** Controls the provider outcome returned by a test probe. */
    private enum ProbeOutcome {
        SUCCESS,
        UNAVAILABLE,
        UNEXPECTED
    }

    /** Provides deterministic embedding outcomes without making network calls. */
    private static final class SequencedEmbeddingClient implements EmbeddingClient {
        private final Queue<ProbeOutcome> probeOutcome;
        private int warmUpInvocationCount;

        private SequencedEmbeddingClient(ProbeOutcome... probeOutcome) {
            this.probeOutcome = new ArrayDeque<>(Arrays.asList(probeOutcome));
        }

        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
            throw new AssertionError("keep-alive probes must not call embed(List, LlmGatewayTier)");
        }

        @Override
        public String modelName() {
            return "test-embedding-model";
        }

        @Override
        public void warmUp() {
            warmUpInvocationCount++;
            switch (probeOutcome.remove()) {
                case SUCCESS -> {}
                case UNAVAILABLE -> throw new EmbeddingServiceUnavailableException("provider offline for test");
                case UNEXPECTED -> throw new IllegalStateException("unexpected provider defect");
            }
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }

    /** Supplies deterministic monotonic timestamps for probe duration tests. */
    private static final class SequencedNanoTime implements LongSupplier {
        private static final long GAP_BETWEEN_PROBES_MILLIS = 1_000L;
        private final Queue<Long> nanoTime;

        private SequencedNanoTime(long... probeDurationMillis) {
            nanoTime = new ArrayDeque<>();
            long probeStartNanos = 0;
            for (long probeDurationMillisValue : probeDurationMillis) {
                nanoTime.add(probeStartNanos);
                probeStartNanos += TimeUnit.MILLISECONDS.toNanos(probeDurationMillisValue);
                nanoTime.add(probeStartNanos);
                probeStartNanos += TimeUnit.MILLISECONDS.toNanos(GAP_BETWEEN_PROBES_MILLIS);
            }
        }

        @Override
        public long getAsLong() {
            return nanoTime.remove();
        }
    }
}
