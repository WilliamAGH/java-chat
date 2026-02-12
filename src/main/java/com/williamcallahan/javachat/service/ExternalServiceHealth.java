package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Monitors external service health with exponential backoff for failed services.
 * Prevents unnecessary API calls to services that are known to be down.
 */
@Service
public class ExternalServiceHealth {
    private static final Logger log = LoggerFactory.getLogger(ExternalServiceHealth.class);

    /** Known external service identifiers for type-safe health checks. */
    public static final String SERVICE_QDRANT = "qdrant";

    // Health snapshot message templates
    private static final String HEALTHY_MSG_TEMPLATE = "Healthy (checked %s ago)";
    private static final String UNHEALTHY_CHECKING_MSG = "Unhealthy (checking now...)";
    private static final String UNHEALTHY_RETRY_DUE_MSG = "Unhealthy (retry due)";
    private static final String UNHEALTHY_NEXT_CHECK_TEMPLATE = "Unhealthy (failed %d times, next check in %s)";
    private static final String UNKNOWN_SERVICE_MSG = "Unknown service";

    private final WebClient webClient;
    private final QdrantRestConnection qdrantRestConnection;
    private final List<String> qdrantCollections;
    private final Map<String, ServiceStatus> serviceStatuses = new ConcurrentHashMap<>();

    // Health check intervals
    private static final Duration INITIAL_CHECK_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_CHECK_INTERVAL = Duration.ofDays(1);
    private static final Duration HEALTHY_CHECK_INTERVAL = Duration.ofHours(1);

    /** Timeout for external service health check requests. */
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Creates the health monitor with a WebClient for outbound checks.
     *
     * @param webClientBuilder WebClient builder for outbound checks
     * @param qdrantRestConnection shared Qdrant REST connection details
     * @param appProperties application configuration for Qdrant collection names
     */
    public ExternalServiceHealth(
            WebClient.Builder webClientBuilder,
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties) {
        this.webClient =
                Objects.requireNonNull(webClientBuilder, "webClientBuilder").build();
        this.qdrantRestConnection = Objects.requireNonNull(qdrantRestConnection, "qdrantRestConnection");
        AppProperties requiredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.QdrantCollections collections =
                requiredAppProperties.getQdrant().getCollections();
        this.qdrantCollections = List.of(
                collections.getBooks(), collections.getDocs(), collections.getArticles(), collections.getPdfs());
    }

    /**
     * Seeds known services and performs the initial health checks.
     */
    @PostConstruct
    public void init() {
        // Initialize service statuses
        serviceStatuses.put(SERVICE_QDRANT, new ServiceStatus());

        // Connectivity-only check during bean initialization to avoid racing collection provisioning.
        checkQdrantConnectivity();

        log.info("ExternalServiceHealth initialized, monitoring {} services", serviceStatuses.size());
    }

    /**
     * Verifies Qdrant collections after the application has finished startup initialization.
     *
     * <p>Hybrid collections may be created on {@link ApplicationReadyEvent}; this avoids false negatives
     * during early startup while still enforcing collection presence after initialization.</p>
     *
     * <p>The initial connectivity check from {@link #init()} may still be in-flight (reactive subscribe
     * is non-blocking). Resetting the service status forces an immediate full collection check regardless
     * of whether the connectivity probe has completed.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyQdrantCollectionsAfterStartup() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status != null) {
            status.reset();
        }
        checkQdrantHealth();
    }

    /**
     * Checks whether a service is currently healthy and available for use.
     *
     * <p>This is a pure query — it does not trigger any side effects. Use
     * {@link #triggerRetryIfDue(String)} to schedule a retry when the backoff has elapsed.
     *
     * @param serviceName logical service name (for example, {@link #SERVICE_QDRANT})
     * @return true when the service is healthy or unknown, false when in backoff
     */
    public boolean isHealthy(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        return status == null || status.isHealthy.get();
    }

    /**
     * Triggers a health check retry if the backoff period has elapsed for an unhealthy service.
     *
     * <p>No-op when the service is healthy, unknown, or still within its backoff window.
     *
     * @param serviceName logical service name (for example, {@link #SERVICE_QDRANT})
     */
    public void triggerRetryIfDue(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status == null || status.isHealthy.get()) {
            return;
        }
        if (status.shouldRetryNow(Instant.now()) && SERVICE_QDRANT.equals(serviceName)) {
            checkQdrantHealth();
        }
    }

    /**
     * Provides detailed status information for a service.
     *
     * @param serviceName logical service name
     * @return service status details for UI/diagnostics
     */
    public HealthSnapshot getHealthSnapshot(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status == null) {
            return new HealthSnapshot(serviceName, true, UNKNOWN_SERVICE_MSG, null);
        }

        String message;
        Duration timeUntilNextCheck = null;

        if (status.isHealthy.get()) {
            message = String.format(
                    HEALTHY_MSG_TEMPLATE, formatDuration(Duration.between(status.lastCheck, Instant.now())));
        } else {
            timeUntilNextCheck = Duration.between(Instant.now(), status.lastCheck.plus(status.currentBackoff));

            if (timeUntilNextCheck.isNegative()) {
                message = status.isCheckInProgress() ? UNHEALTHY_CHECKING_MSG : UNHEALTHY_RETRY_DUE_MSG;
                timeUntilNextCheck = Duration.ZERO;
            } else {
                message = String.format(
                        UNHEALTHY_NEXT_CHECK_TEMPLATE,
                        status.consecutiveFailures.get(),
                        formatDuration(timeUntilNextCheck));
            }
        }

        return new HealthSnapshot(serviceName, status.isHealthy.get(), message, timeUntilNextCheck);
    }

    /**
     * Scheduled health check for Qdrant (runs every hour for healthy services).
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    public void scheduledQdrantHealthCheck() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status == null) {
            return;
        }
        if (status.isHealthy.get()) {
            checkQdrantHealth();
            return;
        }
        triggerRetryIfDue(SERVICE_QDRANT);
    }

    private void checkQdrantConnectivity() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status == null) return;
        if (!status.tryStartCheck(Instant.now())) {
            return;
        }

        try {
            String base = qdrantRestConnection.restBaseUrl();
            String connectivityPath = qdrantRestConnection.useTls() ? "/collections" : "/health";
            String connectivityUrl = base + connectivityPath;

            String qdrantApiKey = qdrantRestConnection.apiKey();
            var requestSpec = webClient.get().uri(connectivityUrl);
            if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
                requestSpec = requestSpec.header(QdrantRestConnection.API_KEY_HEADER, qdrantApiKey);
            }

            requestSpec
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .subscribe(
                            ignoredResponse -> {
                                log.info("[HEALTH] Qdrant connectivity check succeeded");
                                status.markHealthy();
                            },
                            healthCheckFailure -> {
                                status.markUnhealthy();
                                log.warn(
                                        "[HEALTH] Qdrant connectivity check failed (exception type: {}, message: {}) - Will retry in {}",
                                        healthCheckFailure.getClass().getSimpleName(),
                                        healthCheckFailure.getMessage(),
                                        formatDuration(status.currentBackoff));
                            });
        } catch (RuntimeException connectivityException) {
            status.markUnhealthy();
            log.warn(
                    "[HEALTH] Qdrant connectivity check failed before subscription (exception type: {}) - Will retry in {}",
                    connectivityException.getClass().getSimpleName(),
                    formatDuration(status.currentBackoff),
                    connectivityException);
        }
    }

    private void checkQdrantHealth() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status == null) return;
        if (!status.tryStartCheck(Instant.now())) {
            return;
        }

        if (qdrantCollections.isEmpty()) {
            status.markUnhealthy();
            log.warn("[HEALTH] Qdrant health check skipped: no collections configured under app.qdrant.collections.*");
            return;
        }

        try {
            String base = qdrantRestConnection.restBaseUrl();
            String qdrantApiKey = qdrantRestConnection.apiKey();
            List<Mono<Void>> checks = new ArrayList<>(qdrantCollections.size());
            for (String collection : qdrantCollections) {
                if (collection == null || collection.isBlank()) {
                    continue;
                }
                String collectionUrl = base + "/collections/" + collection;
                var requestSpec = webClient.get().uri(collectionUrl);
                if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
                    requestSpec = requestSpec.header(QdrantRestConnection.API_KEY_HEADER, qdrantApiKey);
                }
                checks.add(requestSpec
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(HEALTH_CHECK_TIMEOUT)
                        .then());
            }

            if (checks.isEmpty()) {
                status.markUnhealthy();
                log.warn("[HEALTH] Qdrant health check skipped: configured collections are blank");
                return;
            }

            Mono.whenDelayError(checks)
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .subscribe(
                            ignoredResponse -> {
                                // Mono<Void> has no onNext payload.
                            },
                            healthCheckFailure -> {
                                status.markUnhealthy();
                                log.warn(
                                        "[HEALTH] Qdrant health check failed (exception type: {}) - Will retry in {}",
                                        healthCheckFailure.getClass().getSimpleName(),
                                        formatDuration(status.currentBackoff));
                            },
                            () -> {
                                status.markHealthy();
                                log.info(
                                        "[HEALTH] Qdrant health check succeeded (all {} collections present)",
                                        checks.size());
                            });
        } catch (RuntimeException healthCheckException) {
            status.markUnhealthy();
            log.warn(
                    "[HEALTH] Qdrant health check failed before subscription (exception type: {}) - Will retry in {}",
                    healthCheckException.getClass().getSimpleName(),
                    formatDuration(status.currentBackoff),
                    healthCheckException);
        }
    }

    /**
     * Forces a health check for a specific service.
     *
     * @param serviceName logical service name
     */
    public void forceHealthCheck(String serviceName) {
        if (SERVICE_QDRANT.equals(serviceName)) {
            checkQdrantHealth();
        }
    }

    /**
     * Resets a service's health status (useful for manual intervention).
     *
     * @param serviceName logical service name
     */
    public void resetServiceStatus(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status != null) {
            status.reset();
            if (SERVICE_QDRANT.equals(serviceName)) {
                log.info("Reset health status for Qdrant");
            } else {
                log.info("Reset health status for service");
            }

            // Trigger immediate health check
            forceHealthCheck(serviceName);
        }
    }

    private String formatDuration(Duration duration) {
        if (duration.isNegative()) {
            return "0m";
        }

        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;

        if (days > 0) {
            return String.format("%dd %dh", days, hours);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    /**
     * Internal class to track service status with exponential backoff
     */
    private static class ServiceStatus {
        final AtomicBoolean isHealthy = new AtomicBoolean(false);
        final AtomicBoolean checkInProgress = new AtomicBoolean(false);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile Instant lastCheck = Instant.now();
        volatile Duration currentBackoff = INITIAL_CHECK_INTERVAL;

        ServiceStatus() {}

        void markHealthy() {
            isHealthy.set(true);
            consecutiveFailures.set(0);
            currentBackoff = HEALTHY_CHECK_INTERVAL;
            lastCheck = Instant.now();
            checkInProgress.set(false);
        }

        void markUnhealthy() {
            isHealthy.set(false);
            int failures = consecutiveFailures.incrementAndGet();

            currentBackoff = computeBackoffDuration(failures);
            lastCheck = Instant.now();
            checkInProgress.set(false);
        }

        void reset() {
            isHealthy.set(false);
            consecutiveFailures.set(0);
            currentBackoff = INITIAL_CHECK_INTERVAL;
            lastCheck = Instant.EPOCH; // Force immediate check
            checkInProgress.set(false);
        }

        boolean tryStartCheck(Instant checkStartTime) {
            if (!checkInProgress.compareAndSet(false, true)) {
                return false;
            }
            lastCheck = checkStartTime;
            return true;
        }

        boolean shouldRetryNow(Instant evaluationTime) {
            if (checkInProgress.get()) {
                return false;
            }
            Instant nextCheck = lastCheck.plus(currentBackoff);
            return !evaluationTime.isBefore(nextCheck);
        }

        boolean isCheckInProgress() {
            return checkInProgress.get();
        }

        private Duration computeBackoffDuration(int failureCount) {
            Duration resolvedBackoff = INITIAL_CHECK_INTERVAL;
            for (int failureIndex = 1; failureIndex < failureCount; failureIndex++) {
                Duration doubledBackoff;
                try {
                    doubledBackoff = resolvedBackoff.multipliedBy(2);
                } catch (ArithmeticException overflowFailure) {
                    return MAX_CHECK_INTERVAL;
                }
                if (doubledBackoff.compareTo(MAX_CHECK_INTERVAL) >= 0) {
                    return MAX_CHECK_INTERVAL;
                }
                resolvedBackoff = doubledBackoff;
            }
            return resolvedBackoff;
        }
    }

    /**
     * Immutable snapshot of service health status for UI and diagnostics.
     */
    public static class HealthSnapshot {
        private final String name;
        private final boolean healthy;
        private final String message;
        private final Optional<Duration> timeUntilNextCheck;

        /**
         * Creates a snapshot of service health status.
         *
         * @param name service identifier
         * @param isHealthy current health state
         * @param message human-readable status description
         * @param timeUntilNextCheck time until next check (null wraps to empty Optional)
         */
        public HealthSnapshot(String name, boolean isHealthy, String message, Duration timeUntilNextCheck) {
            this.name = name;
            this.healthy = isHealthy;
            this.message = message;
            this.timeUntilNextCheck = Optional.ofNullable(timeUntilNextCheck);
        }

        /** Provides the service identifier. */
        public String name() {
            return name;
        }

        /** Indicates whether the service is currently healthy. */
        public boolean isHealthy() {
            return healthy;
        }

        /** Describes the current health state in human-readable form. */
        public String message() {
            return message;
        }

        /** Provides the time until the next scheduled check, if applicable. */
        public Optional<Duration> timeUntilNextCheck() {
            return timeUntilNextCheck;
        }
    }
}
