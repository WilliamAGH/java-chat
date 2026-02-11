package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persistent rate limit state manager that survives application restarts.
 * Tracks actual rate limit windows and implements intelligent backoff.
 */
@Component
public class RateLimitState {
    private static final Logger log = LoggerFactory.getLogger(RateLimitState.class);
    private static final String STATE_FILE = "./data/rate-limit-state.json";

    private final ObjectMapper objectMapper;
    private final RateLimitHeaderParser headerParser;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Object saveLock = new Object();

    private Map<String, ProviderState> providerStates = new ConcurrentHashMap<>();

    // Prefer Spring Boot's auto-configured ObjectMapper (with modules) and fall back to a local one.
    /**
     * Creates persistent rate limit state storage using the provided ObjectMapper configuration when available.
     */
    public RateLimitState(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.headerParser = new RateLimitHeaderParser();
    }

    /**
     * Loads persisted state and schedules periodic persistence to survive application restarts.
     */
    @PostConstruct
    public void init() {
        loadState();
        // Periodically save state every 5 minutes
        scheduler.scheduleAtFixedRate(this::safeSaveState, 5, 5, TimeUnit.MINUTES);
        log.info("RateLimitState initialized with persistent storage at: {}", STATE_FILE);
    }

    /**
     * Persists state and shuts down background tasks during application teardown.
     */
    @PreDestroy
    public void shutdown() {
        // Be defensive during shutdown so failures here never take down the app with NoClassDefFoundError
        try {
            safeSaveState();
        } catch (Exception shutdownException) {
            // Use stderr during teardown - logging framework may be partially unloaded
            System.err.println("[RateLimitState] Failed to save state on shutdown: "
                    + shutdownException.getClass().getName() + ": " + shutdownException.getMessage());
        } catch (NoClassDefFoundError classLoadError) {
            // Explicitly handle classloading issues during shutdown (expected in some JVM teardown scenarios)
            System.err.println(
                    "[RateLimitState] Classloader issue during shutdown (expected): " + classLoadError.getMessage());
        }
        scheduler.shutdown();
    }

    /**
     * Record a rate limit hit with proper backoff calculation
     */
    public void recordRateLimit(String provider, Instant resetTime, String rateLimitWindow) {
        ProviderState state = providerStates.computeIfAbsent(provider, providerKey -> new ProviderState());

        // Parse rate limit window (e.g., "24h", "1d", "6h")
        Duration windowDuration = parseRateLimitWindow(rateLimitWindow);

        // If we don't have a reset time from headers, calculate based on window
        if (resetTime == null) {
            resetTime = Instant.now().plus(windowDuration);
        }

        state.rateLimitedUntil = resetTime;
        int failures = state.consecutiveFailures.incrementAndGet();
        state.totalFailures.incrementAndGet();
        state.lastFailure = Instant.now();

        // Implement exponential backoff for repeated failures
        if (failures > 1) {
            Duration additionalBackoff = Duration.ofHours((long) Math.pow(2, failures - 1));
            Duration maxBackoff = Duration.ofDays(7); // Never back off more than a week

            if (additionalBackoff.compareTo(maxBackoff) > 0) {
                additionalBackoff = maxBackoff;
            }

            state.rateLimitedUntil = state.rateLimitedUntil.plus(additionalBackoff);
            log.warn(
                    "[{}] Consecutive failures (count={}). Extended backoff until {}",
                    sanitizeLogValue(provider),
                    failures,
                    state.rateLimitedUntil);
        }

        safeSaveState();
        log.info("[{}] Rate limited until {}", sanitizeLogValue(provider), state.rateLimitedUntil);
    }

    /**
     * Record a successful API call
     */
    public void recordSuccess(String provider) {
        ProviderState state = providerStates.computeIfAbsent(provider, providerKey -> new ProviderState());
        state.consecutiveFailures.set(0);
        state.lastSuccess = Instant.now();
        state.totalSuccesses.incrementAndGet();
    }

    /**
     * Check if a provider is currently available
     */
    public boolean isAvailable(String provider) {
        ProviderState state = providerStates.get(provider);
        if (state == null) {
            return true;
        }

        if (state.rateLimitedUntil != null && Instant.now().isBefore(state.rateLimitedUntil)) {
            return false;
        }

        // Clear rate limit if it has expired
        if (state.rateLimitedUntil != null && Instant.now().isAfter(state.rateLimitedUntil)) {
            state.rateLimitedUntil = null;
            safeSaveState();
        }

        return true;
    }

    /**
     * Get remaining wait time for a provider
     */
    public Duration getRemainingWaitTime(String provider) {
        ProviderState state = providerStates.get(provider);
        if (state == null || state.rateLimitedUntil == null) {
            return Duration.ZERO;
        }

        Duration remaining = Duration.between(Instant.now(), state.rateLimitedUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Parses rate limit window strings like "24h", "1d", "6h", defaulting to 1 hour.
     */
    private Duration parseRateLimitWindow(String window) {
        return headerParser.parseDurationOrDefault(window, Duration.ofHours(1));
    }

    /**
     * Loads persisted state from disk. Returns false if loading fails, allowing caller to decide
     * whether to proceed with empty state or surface the error.
     *
     * @return true if state was loaded successfully or file doesn't exist, false on parse/IO error
     */
    private boolean loadState() {
        File file = new File(STATE_FILE);
        if (!file.exists()) {
            log.info("No persisted rate limit state found, starting fresh");
            return true;
        }

        try {
            PersistedState persistedState = objectMapper.readValue(file, PersistedState.class);
            if (persistedState != null && persistedState.getProviders() != null) {
                providerStates = new ConcurrentHashMap<>(persistedState.getProviders());
                log.info("Loaded rate limit state for {} providers", providerStates.size());
                logCurrentRateLimitStatus();
            }
            return true;
        } catch (IOException exception) {
            String safeMessage = sanitizeLogValue(exception.getMessage());
            log.error(
                    "Failed to load rate limit state from {}: {} - {}",
                    STATE_FILE,
                    exception.getClass().getSimpleName(),
                    safeMessage);
            log.warn(
                    "Continuing with empty rate limit state; previously rate-limited providers may be retried prematurely");
            return false;
        }
    }

    private void logCurrentRateLimitStatus() {
        for (Map.Entry<String, ProviderState> entry : providerStates.entrySet()) {
            if (!isAvailable(entry.getKey())) {
                Duration remaining = getRemainingWaitTime(entry.getKey());
                log.warn("[{}] Rate limited for {} more", sanitizeLogValue(entry.getKey()), formatDuration(remaining));
            }
        }
    }

    /**
     * Persists state to disk with explicit failure tracking. Returns false if save fails,
     * allowing callers to track persistence health.
     *
     * @return true if save succeeded, false if persistence failed
     */
    private boolean trySaveState() {
        try {
            saveState();
            return true;
        } catch (IOException ioException) {
            String safeMessage = sanitizeLogValue(ioException.getMessage());
            log.error(
                    "Failed to persist rate limit state to {}: {} - {}",
                    STATE_FILE,
                    ioException.getClass().getSimpleName(),
                    safeMessage);
            return false;
        } catch (RuntimeException runtimeException) {
            String safeMessage = sanitizeLogValue(runtimeException.getMessage());
            log.error(
                    "Unexpected error persisting rate limit state: {} - {}",
                    runtimeException.getClass().getSimpleName(),
                    safeMessage);
            return false;
        }
    }

    /**
     * Saves state without throwing. Used for scheduled/lifecycle saves where failures are non-fatal.
     * Logs errors with full context but continues execution.
     */
    private void safeSaveState() {
        if (!trySaveState()) {
            log.warn("Rate limit state not persisted; will be rebuilt from scratch on next startup");
        }
    }

    private void saveState() throws IOException {
        synchronized (saveLock) {
            File file = new File(STATE_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create state directory: " + parent);
            }

            PersistedState persistedState = new PersistedState();
            persistedState.setProviders(new ConcurrentHashMap<>(providerStates));
            persistedState.setSavedAt(Instant.now());

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, persistedState);
        }
    }

    private String formatDuration(Duration duration) {
        return RateLimitHeaderParser.formatDuration(duration);
    }

    private static String sanitizeLogValue(String rawValue) {
        if (rawValue == null) {
            return "null";
        }
        return rawValue.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * Defines the persisted JSON payload for rate limit state storage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersistedState {
        private Map<String, ProviderState> providers;
        private Instant savedAt;

        /**
         * Returns the persisted provider state map.
         */
        public Map<String, ProviderState> getProviders() {
            return providers == null ? Map.of() : providers;
        }

        /**
         * Sets the persisted provider state map, defaulting to an empty map when null.
         */
        public void setProviders(Map<String, ProviderState> providers) {
            this.providers = providers == null ? Map.of() : Map.copyOf(providers);
        }

        /**
         * Returns the last time the state was saved.
         */
        public Instant getSavedAt() {
            return savedAt;
        }

        /**
         * Sets the last saved timestamp for the persisted state.
         */
        public void setSavedAt(Instant savedAt) {
            this.savedAt = savedAt;
        }
    }

    /**
     * Holds per-provider timestamps and counters used to compute backoff and availability.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderState {
        private volatile Instant rateLimitedUntil;
        private volatile Instant lastSuccess;
        private volatile Instant lastFailure;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong totalSuccesses = new AtomicLong(0);
        private final AtomicLong totalFailures = new AtomicLong(0);

        /**
         * Returns the timestamp when the provider becomes available again.
         */
        public Instant getRateLimitedUntil() {
            return rateLimitedUntil;
        }

        /**
         * Sets the timestamp when the provider becomes available again.
         */
        public void setRateLimitedUntil(Instant rateLimitedUntil) {
            this.rateLimitedUntil = rateLimitedUntil;
        }

        /**
         * Returns the timestamp of the last successful call.
         */
        public Instant getLastSuccess() {
            return lastSuccess;
        }

        /**
         * Sets the timestamp of the last successful call.
         */
        public void setLastSuccess(Instant lastSuccess) {
            this.lastSuccess = lastSuccess;
        }

        /**
         * Returns the timestamp of the last failure.
         */
        public Instant getLastFailure() {
            return lastFailure;
        }

        /**
         * Sets the timestamp of the last failure.
         */
        public void setLastFailure(Instant lastFailure) {
            this.lastFailure = lastFailure;
        }

        /**
         * Returns the current consecutive failure count.
         */
        public int getConsecutiveFailures() {
            return consecutiveFailures.get();
        }

        /**
         * Sets the current consecutive failure count.
         */
        public void setConsecutiveFailures(int consecutiveFailures) {
            this.consecutiveFailures.set(consecutiveFailures);
        }

        /**
         * Returns the total number of successful calls recorded.
         */
        public long getTotalSuccesses() {
            return totalSuccesses.get();
        }

        /**
         * Sets the total number of successful calls recorded.
         */
        public void setTotalSuccesses(long totalSuccesses) {
            this.totalSuccesses.set(totalSuccesses);
        }

        /**
         * Returns the total number of failed calls recorded.
         */
        public long getTotalFailures() {
            return totalFailures.get();
        }

        /**
         * Sets the total number of failed calls recorded.
         */
        public void setTotalFailures(long totalFailures) {
            this.totalFailures.set(totalFailures);
        }
    }
}
