package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Persistent rate limit state manager that survives application restarts.
 * Tracks provider-declared rate limit windows and request outcomes.
 */
@Component
public class RateLimitState {
    private static final Logger log = LoggerFactory.getLogger(RateLimitState.class);
    private static final Path DEFAULT_STATE_FILE = Path.of("./data/rate-limit-state.json");
    private static final String RATE_LIMIT_TEMPORARY_FILE_PREFIX = "rate-limit-state.";

    private final ObjectMapper objectMapper;
    private final RateLimitHeaderParser headerParser;
    private final Path stateFile;
    private final Object saveLock = new Object();

    private Map<String, ProviderState> providerStates = new ConcurrentHashMap<>();
    private volatile boolean persistenceHealthy = true;
    /** Starts only after persisted state loads successfully, preventing failed-start thread leaks. */
    private ScheduledExecutorService persistenceScheduler;

    /**
     * Creates persistent rate limit state storage from Spring Boot's configured JSON mapper.
     */
    @Autowired
    public RateLimitState(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_STATE_FILE);
    }

    RateLimitState(ObjectMapper objectMapper, Path stateFile) {
        this.objectMapper = objectMapper.copy();
        this.headerParser = new RateLimitHeaderParser();
        this.stateFile =
                Objects.requireNonNull(stateFile, "stateFile").toAbsolutePath().normalize();
    }

    /**
     * Loads persisted state and schedules periodic persistence to survive application restarts.
     */
    @PostConstruct
    public void init() {
        loadState();
        persistenceScheduler = Executors.newSingleThreadScheduledExecutor();
        persistenceScheduler.scheduleAtFixedRate(this::safeSaveState, 5, 5, TimeUnit.MINUTES);
        log.info("RateLimitState initialized with persistent storage at: {}", stateFile);
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
        ScheduledExecutorService activePersistenceScheduler = persistenceScheduler;
        if (activePersistenceScheduler != null) {
            activePersistenceScheduler.shutdown();
        }
    }

    /**
     * Records a rate limit hit using the provider-declared reset time.
     */
    public void recordRateLimit(String provider, Instant resetTime, String rateLimitWindow) {
        ProviderState state = providerStates.computeIfAbsent(provider, providerKey -> new ProviderState());

        // Parse rate limit window (e.g., "24h", "1d", "6h")
        Duration windowDuration = parseRateLimitWindow(rateLimitWindow);

        // If we don't have a reset time from headers, calculate based on window
        if (resetTime == null) {
            resetTime = Instant.now().plus(windowDuration);
        }

        state.recordRateLimit(resetTime, Instant.now());

        safeSaveState();
        log.info("[{}] Rate limited until {}", sanitizeLogValue(provider), state.getRateLimitedUntil());
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
        if (!persistenceHealthy) {
            return false;
        }
        ProviderState state = providerStates.get(provider);
        if (state == null) {
            return true;
        }

        ProviderState.RateLimitWindowEvaluation rateLimitWindowEvaluation =
                state.evaluateRateLimitWindow(Instant.now());
        if (rateLimitWindowEvaluation == ProviderState.RateLimitWindowEvaluation.EXPIRED) {
            safeSaveState();
        }

        return persistenceHealthy && rateLimitWindowEvaluation != ProviderState.RateLimitWindowEvaluation.RATE_LIMITED;
    }

    /**
     * Get remaining wait time for a provider
     */
    public Duration getRemainingWaitTime(String provider) {
        ProviderState state = providerStates.get(provider);
        if (state == null) {
            return Duration.ZERO;
        }

        Instant rateLimitedUntil = state.getRateLimitedUntil();
        if (rateLimitedUntil == null) {
            return Duration.ZERO;
        }

        Duration remaining = Duration.between(Instant.now(), rateLimitedUntil);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Parses rate limit window strings like "24h", "1d", "6h", defaulting to 1 hour.
     */
    private Duration parseRateLimitWindow(String window) {
        return headerParser.parseDurationOrDefault(window, Duration.ofHours(1));
    }

    /**
     * Loads persisted state from disk and fails closed when an existing state file is unreadable.
     */
    private void loadState() {
        if (Files.notExists(stateFile)) {
            log.info("No persisted rate limit state found, starting fresh");
            return;
        }

        try {
            PersistedState persistedState = objectMapper.readValue(stateFile.toFile(), PersistedState.class);
            if (persistedState != null && persistedState.getProviders() != null) {
                providerStates = new ConcurrentHashMap<>(persistedState.getProviders());
                log.info("Loaded rate limit state for {} providers", providerStates.size());
                logCurrentRateLimitStatus();
            }
        } catch (IOException exception) {
            String safeMessage = sanitizeLogValue(exception.getMessage());
            log.error(
                    "Failed to load rate limit state from {}: {} - {}",
                    stateFile,
                    exception.getClass().getSimpleName(),
                    safeMessage);
            throw new IllegalStateException("Existing rate limit state is unreadable", exception);
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
            persistenceHealthy = true;
            return true;
        } catch (IOException ioException) {
            persistenceHealthy = false;
            String safeMessage = sanitizeLogValue(ioException.getMessage());
            log.error(
                    "Failed to persist rate limit state to {}: {} - {}",
                    stateFile,
                    ioException.getClass().getSimpleName(),
                    safeMessage);
            return false;
        } catch (RuntimeException runtimeException) {
            persistenceHealthy = false;
            String safeMessage = sanitizeLogValue(runtimeException.getMessage());
            log.error(
                    "Unexpected error persisting rate limit state: {} - {}",
                    runtimeException.getClass().getSimpleName(),
                    safeMessage);
            return false;
        }
    }

    /**
     * Saves state without throwing and closes provider admission until persistence recovers.
     */
    private void safeSaveState() {
        if (!trySaveState()) {
            log.warn("Rate limit state persistence is unhealthy; provider admission remains closed");
        }
    }

    private void saveState() throws IOException {
        synchronized (saveLock) {
            Path stateDirectory = Objects.requireNonNull(stateFile.getParent(), "stateFile parent");
            Files.createDirectories(stateDirectory);

            PersistedState persistedState = new PersistedState();
            persistedState.setProviders(new ConcurrentHashMap<>(providerStates));
            persistedState.setSavedAt(Instant.now());

            Path temporaryStateFile = Files.createTempFile(stateDirectory, RATE_LIMIT_TEMPORARY_FILE_PREFIX, ".tmp");
            try {
                byte[] serializedState =
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(persistedState);
                try (FileChannel stateOutputChannel = FileChannel.open(temporaryStateFile, StandardOpenOption.WRITE)) {
                    ByteBuffer serializedStateBuffer = ByteBuffer.wrap(serializedState);
                    while (serializedStateBuffer.hasRemaining()) {
                        stateOutputChannel.write(serializedStateBuffer);
                    }
                    stateOutputChannel.force(true);
                }
                Files.move(
                        temporaryStateFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel stateDirectoryChannel = FileChannel.open(stateDirectory, StandardOpenOption.READ)) {
                    stateDirectoryChannel.force(true);
                }
            } finally {
                Files.deleteIfExists(temporaryStateFile);
            }
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
        /** Describes the provider's rate-limit window at a single atomic evaluation point. */
        enum RateLimitWindowEvaluation {
            AVAILABLE,
            RATE_LIMITED,
            EXPIRED
        }

        private Instant rateLimitedUntil;
        private volatile Instant lastSuccess;
        private volatile Instant lastFailure;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong totalSuccesses = new AtomicLong(0);
        private final AtomicLong totalFailures = new AtomicLong(0);

        synchronized void recordRateLimit(Instant rateLimitDeadline, Instant failedAt) {
            boolean activeWindow = rateLimitedUntil != null && failedAt.isBefore(rateLimitedUntil);
            if (!activeWindow || rateLimitDeadline.isAfter(rateLimitedUntil)) {
                rateLimitedUntil = rateLimitDeadline;
            }
            consecutiveFailures.incrementAndGet();
            totalFailures.incrementAndGet();
            lastFailure = failedAt;
        }

        synchronized RateLimitWindowEvaluation evaluateRateLimitWindow(Instant checkedAt) {
            if (rateLimitedUntil == null) {
                return RateLimitWindowEvaluation.AVAILABLE;
            }
            if (checkedAt.isBefore(rateLimitedUntil)) {
                return RateLimitWindowEvaluation.RATE_LIMITED;
            }
            rateLimitedUntil = null;
            return RateLimitWindowEvaluation.EXPIRED;
        }

        /**
         * Returns the timestamp when the provider becomes available again.
         */
        public synchronized Instant getRateLimitedUntil() {
            return rateLimitedUntil;
        }

        /**
         * Sets the timestamp when the provider becomes available again.
         */
        public synchronized void setRateLimitedUntil(Instant rateLimitedUntil) {
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
