package com.williamcallahan.javachat.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the configured embedding model resident by probing the provider on a fixed cadence.
 *
 * <p>OpenAI-compatible embedding providers scale to zero when idle: serverless cloud hosts
 * (Novita) spin the model down between bursts, and self-hosted servers (LM Studio, Ollama)
 * unload after an idle TTL. The first request after either pays the full model spin-up on
 * the user's critical path — observed in production at 53.5s on a live chat turn and
 * reproduced at 23.7s cold versus 1.0s warm against the same provider. A probe cadence
 * below common scale-down windows keeps the model resident so user requests never pay the
 * cold start. Probe failures are logged as a cold-start risk and never affect request
 * handling.</p>
 */
@Component
public class EmbeddingModelKeepAlive {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelKeepAlive.class);

    /** Probe cadence below the 5-minute idle-unload default of common model servers. */
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 240_000L;

    /** Delay before the first probe so the provider warms up right after startup. */
    private static final long STARTUP_WARMUP_DELAY_MILLIS = 5_000L;

    /** Probe latency above this means the model was cold and a reload just happened. */
    private static final long COLD_MODEL_WARN_THRESHOLD_MILLIS = 5_000L;

    private static final List<String> KEEP_ALIVE_PROBE_TEXTS = List.of("embedding keep-alive probe");

    private final EmbeddingClient embeddingClient;

    /**
     * Creates the keep-alive prober for the active embedding provider.
     */
    public EmbeddingModelKeepAlive(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /**
     * Probes the embedding provider so the model stays loaded between user requests.
     *
     * <p>A failed probe is a monitoring signal, not a request failure: it is logged at WARN
     * and the next tick retries. Unexpected runtime failures propagate to the scheduler's
     * error handler rather than being swallowed here.</p>
     */
    @Scheduled(initialDelay = STARTUP_WARMUP_DELAY_MILLIS, fixedDelay = KEEP_ALIVE_INTERVAL_MILLIS)
    public void keepEmbeddingModelWarm() {
        long probeStartMillis = System.currentTimeMillis();
        try {
            embeddingClient.embed(KEEP_ALIVE_PROBE_TEXTS);
        } catch (EmbeddingServiceUnavailableException exception) {
            log.warn(
                    "[EMBEDDING] Keep-alive probe failed; the next chat request may pay a model cold start", exception);
            return;
        }
        long probeDurationMillis = System.currentTimeMillis() - probeStartMillis;
        if (probeDurationMillis > COLD_MODEL_WARN_THRESHOLD_MILLIS) {
            log.warn(
                    "[EMBEDDING] Keep-alive probe took {}ms — embedding model was cold and has been reloaded",
                    probeDurationMillis);
        } else {
            log.debug("[EMBEDDING] Keep-alive probe completed in {}ms", probeDurationMillis);
        }
    }
}
