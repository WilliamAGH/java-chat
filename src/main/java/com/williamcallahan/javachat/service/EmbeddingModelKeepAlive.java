package com.williamcallahan.javachat.service;

import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Probes the configured embedding provider on a fixed cadence and reports its lifecycle state.
 *
 * <p>OpenAI-compatible embedding providers scale to zero when idle: serverless cloud hosts
 * (Novita) spin the model down between bursts, and self-hosted servers (LM Studio, Ollama)
 * unload after an idle TTL. The first request after either pays the full model spin-up on
 * the user's critical path — observed in production at 53.5s on a live chat turn and
 * reproduced at 23.7s cold versus 1.0s warm against the same provider. A probe cadence
 * below common scale-down windows can reduce how often user requests pay that startup cost.
 * Probe latency alone cannot prove a remote model reload, so slow successful probes are
 * classified separately from failures and exposed through application health.</p>
 */
@Component
public class EmbeddingModelKeepAlive implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelKeepAlive.class);

    /** Probe cadence below the 5-minute idle-unload default of common model servers. */
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 240_000L;

    /** Delay before the first probe so the provider warms up right after startup. */
    private static final long STARTUP_WARMUP_DELAY_MILLIS = 5_000L;

    /** Probe latency above this marks a slow provider response without inferring its remote cause. */
    private static final long SLOW_PROBE_THRESHOLD_MILLIS = 5_000L;

    /** Rechecks an unavailable provider before container health exhausts its retry budget. */
    private static final long UNAVAILABLE_RECOVERY_INTERVAL_MILLIS = 30_000L;

    /** Escalates only after a probe condition repeats on the next observation. */
    private static final int REPEATED_PROBE_ALERT_COUNT = 2;

    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final EmbeddingClient embeddingClient;
    private final String modelName;
    private final LongSupplier nanoTime;
    private volatile EmbeddingProbeSnapshot latestProbe = EmbeddingProbeSnapshot.initializing();

    /**
     * Creates the keep-alive prober for the active embedding provider.
     */
    @Autowired
    public EmbeddingModelKeepAlive(EmbeddingClient embeddingClient) {
        this(embeddingClient, System::nanoTime);
    }

    EmbeddingModelKeepAlive(EmbeddingClient embeddingClient, LongSupplier nanoTime) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.modelName = Objects.requireNonNull(embeddingClient.modelName(), "embeddingClient.modelName");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Probes the embedding provider so the model stays loaded between user requests.
     *
     * <p>A failed probe is a monitoring signal, not a request failure: it is logged at WARN
     * and the next tick retries. Unexpected runtime failures propagate to the scheduler's
     * error handler rather than being swallowed here.</p>
     */
    // fixedRate keeps probe *starts* on the cadence: with fixedDelay a slow cold
    // start would push the next probe past the provider's idle-unload TTL.
    @Scheduled(initialDelay = STARTUP_WARMUP_DELAY_MILLIS, fixedRate = KEEP_ALIVE_INTERVAL_MILLIS)
    public void keepEmbeddingModelWarm() {
        probeEmbeddingModel();
    }

    /** Retries only unavailable providers so health can recover promptly after a transient outage. */
    @Scheduled(initialDelay = UNAVAILABLE_RECOVERY_INTERVAL_MILLIS, fixedDelay = UNAVAILABLE_RECOVERY_INTERVAL_MILLIS)
    void retryUnavailableEmbeddingModel() {
        if (latestProbe.lifecycle() == EmbeddingProbeLifecycle.UNAVAILABLE) {
            probeEmbeddingModel();
        }
    }

    private synchronized void probeEmbeddingModel() {
        long probeStartNanos = nanoTime.getAsLong();
        try {
            embeddingClient.warmUp();
        } catch (RuntimeException exception) {
            recordFailure(elapsedMillis(probeStartNanos), exception);
            if (exception instanceof EmbeddingServiceUnavailableException) {
                return;
            }
            throw exception;
        }
        recordSuccess(elapsedMillis(probeStartNanos));
    }

    /**
     * Projects the latest completed embedding probe into the application health endpoint.
     *
     * @return DOWN until a probe succeeds and after the latest probe fails; UP otherwise
     */
    @Override
    public Health health() {
        EmbeddingProbeSnapshot probeSnapshot = latestProbe;
        Health.Builder health = probeSnapshot.ready() ? Health.up() : Health.down();
        return health.withDetail("model", modelName)
                .withDetail("lifecycle", probeSnapshot.lifecycle())
                .withDetail("lastProbeDurationMs", probeSnapshot.durationMillis())
                .withDetail("consecutiveSlowProbes", probeSnapshot.consecutiveSlowProbes())
                .withDetail("consecutiveFailures", probeSnapshot.consecutiveFailures())
                .build();
    }

    private long elapsedMillis(long probeStartNanos) {
        return (nanoTime.getAsLong() - probeStartNanos) / NANOS_PER_MILLISECOND;
    }

    private void recordSuccess(long probeDurationMillis) {
        String logSafeModelName = modelName.replace("\r", "\\r").replace("\n", "\\n");
        EmbeddingProbeSnapshot previousProbe = latestProbe;
        int previousSlowProbeCount = previousProbe.consecutiveSlowProbes();
        int previousFailureCount = previousProbe.consecutiveFailures();

        if (probeDurationMillis > SLOW_PROBE_THRESHOLD_MILLIS) {
            int consecutiveSlowProbeCount =
                    previousProbe.lifecycle() == EmbeddingProbeLifecycle.SLOW ? previousSlowProbeCount + 1 : 1;
            latestProbe = new EmbeddingProbeSnapshot(
                    EmbeddingProbeLifecycle.SLOW, probeDurationMillis, consecutiveSlowProbeCount, 0);
            if (previousFailureCount > 0) {
                log.atInfo().log(() -> "event=embedding_model_probe_recovered outcome=success model="
                        + logSafeModelName + " durationMs=" + probeDurationMillis + " lifecycle="
                        + EmbeddingProbeLifecycle.SLOW + " previousFailures=" + previousFailureCount);
            } else if (consecutiveSlowProbeCount == 1) {
                log.atInfo().log(() -> "event=embedding_model_probe_slow outcome=success model="
                        + logSafeModelName + " durationMs=" + probeDurationMillis + " consecutiveSlowProbes="
                        + consecutiveSlowProbeCount);
            } else if (consecutiveSlowProbeCount == REPEATED_PROBE_ALERT_COUNT) {
                log.atWarn()
                        .log(() -> "event=embedding_model_probe_slow_loop outcome=success model="
                                + logSafeModelName + " durationMs=" + probeDurationMillis + " consecutiveSlowProbes="
                                + consecutiveSlowProbeCount);
            } else {
                log.atDebug().log(() -> "event=embedding_model_probe_slow outcome=success model="
                        + logSafeModelName + " durationMs=" + probeDurationMillis + " consecutiveSlowProbes="
                        + consecutiveSlowProbeCount);
            }
            return;
        }

        latestProbe = new EmbeddingProbeSnapshot(EmbeddingProbeLifecycle.READY, probeDurationMillis, 0, 0);
        if (previousSlowProbeCount > 0 || previousFailureCount > 0) {
            log.atInfo().log(() -> "event=embedding_model_probe_recovered outcome=success model="
                    + logSafeModelName + " durationMs=" + probeDurationMillis + " previousSlowProbes="
                    + previousSlowProbeCount + " previousFailures=" + previousFailureCount);
        } else if (previousProbe.lifecycle() == EmbeddingProbeLifecycle.INITIALIZING) {
            log.atInfo().log(() -> "event=embedding_model_probe_ready outcome=success model=" + logSafeModelName
                    + " durationMs=" + probeDurationMillis);
        } else {
            log.atDebug().log(() -> "event=embedding_model_probe_ready outcome=success model=" + logSafeModelName
                    + " durationMs=" + probeDurationMillis);
        }
    }

    private void recordFailure(long probeDurationMillis, RuntimeException exception) {
        String logSafeModelName = modelName.replace("\r", "\\r").replace("\n", "\\n");
        EmbeddingProbeSnapshot previousProbe = latestProbe;
        int consecutiveFailureCount = previousProbe.lifecycle() == EmbeddingProbeLifecycle.UNAVAILABLE
                ? previousProbe.consecutiveFailures() + 1
                : 1;
        latestProbe = new EmbeddingProbeSnapshot(
                EmbeddingProbeLifecycle.UNAVAILABLE, probeDurationMillis, 0, consecutiveFailureCount);
        if (consecutiveFailureCount == 1) {
            log.atWarn()
                    .setCause(exception)
                    .log(() -> "event=embedding_model_probe_failed outcome=failure model=" + logSafeModelName
                            + " durationMs=" + probeDurationMillis + " consecutiveFailures=" + consecutiveFailureCount);
        } else if (consecutiveFailureCount == REPEATED_PROBE_ALERT_COUNT) {
            log.atError()
                    .setCause(exception)
                    .log(() -> "event=embedding_model_probe_failure_loop outcome=failure model=" + logSafeModelName
                            + " durationMs=" + probeDurationMillis + " consecutiveFailures=" + consecutiveFailureCount);
        } else {
            log.atDebug().log(() -> "event=embedding_model_probe_failed outcome=failure model="
                    + logSafeModelName + " durationMs=" + probeDurationMillis + " consecutiveFailures="
                    + consecutiveFailureCount);
        }
    }

    /** Describes the latest provider probe outcome without inferring remote engine state. */
    private enum EmbeddingProbeLifecycle {
        INITIALIZING,
        READY,
        SLOW,
        UNAVAILABLE
    }

    private record EmbeddingProbeSnapshot(
            EmbeddingProbeLifecycle lifecycle,
            long durationMillis,
            int consecutiveSlowProbes,
            int consecutiveFailures) {
        private static EmbeddingProbeSnapshot initializing() {
            return new EmbeddingProbeSnapshot(EmbeddingProbeLifecycle.INITIALIZING, 0, 0, 0);
        }

        private boolean ready() {
            return lifecycle == EmbeddingProbeLifecycle.READY || lifecycle == EmbeddingProbeLifecycle.SLOW;
        }
    }
}
