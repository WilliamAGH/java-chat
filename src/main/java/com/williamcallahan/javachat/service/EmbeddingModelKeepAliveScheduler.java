package com.williamcallahan.javachat.service;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules embedding health probes only in long-running server processes.
 *
 * <p>CLI ingestion processes retain the health contributor but never add background embedding
 * traffic while their batch workload is active.</p>
 */
@Component
@Profile("!cli & !cli-github")
public final class EmbeddingModelKeepAliveScheduler {
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 240_000L;
    private static final long STARTUP_WARMUP_DELAY_MILLIS = 5_000L;
    private static final long UNAVAILABLE_RECOVERY_INTERVAL_MILLIS = 30_000L;

    private final EmbeddingModelKeepAlive embeddingModelKeepAlive;

    /**
     * Creates the scheduler for the server's embedding health contributor.
     */
    public EmbeddingModelKeepAliveScheduler(EmbeddingModelKeepAlive embeddingModelKeepAlive) {
        this.embeddingModelKeepAlive = Objects.requireNonNull(embeddingModelKeepAlive, "embeddingModelKeepAlive");
    }

    /**
     * Starts probes on a fixed cadence so slow starts do not extend the provider idle window.
     */
    @Scheduled(initialDelay = STARTUP_WARMUP_DELAY_MILLIS, fixedRate = KEEP_ALIVE_INTERVAL_MILLIS)
    public void keepEmbeddingModelWarm() {
        embeddingModelKeepAlive.keepEmbeddingModelWarm();
    }

    /**
     * Rechecks unavailable providers before container health exhausts its retry budget.
     */
    @Scheduled(initialDelay = UNAVAILABLE_RECOVERY_INTERVAL_MILLIS, fixedDelay = UNAVAILABLE_RECOVERY_INTERVAL_MILLIS)
    public void retryUnavailableEmbeddingModel() {
        embeddingModelKeepAlive.retryUnavailableEmbeddingModel();
    }
}
