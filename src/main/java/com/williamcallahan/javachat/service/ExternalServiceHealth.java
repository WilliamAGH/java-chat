package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors external service health with exponential backoff for failed services.
 * Prevents unnecessary API calls to services that are known to be down.
 */
@Service
public class ExternalServiceHealth {
    private static final Logger log = LoggerFactory.getLogger(ExternalServiceHealth.class);

    /** Known external service identifiers for type-safe health checks. */
    public static final String SERVICE_QDRANT = "qdrant";

    private final WebClient webClient;
    private final Map<String, ServiceStatus> serviceStatuses = new ConcurrentHashMap<>();

    /** Qdrant gRPC default port. */
    private static final int QDRANT_GRPC_PORT = 6334;
    /** Qdrant REST default port. */
    private static final int QDRANT_REST_PORT = 6333;
    /** Docker compose gRPC port mapping in this repo. */
    private static final int DOCKER_GRPC_PORT = 8086;
    /** Docker compose REST port mapping in this repo. */
    private static final int DOCKER_REST_PORT = 8087;

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int qdrantPort;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean qdrantSsl;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String qdrantApiKey;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:java-chat}")
    private String qdrantCollection;

    // Health check intervals
    private static final Duration INITIAL_CHECK_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_CHECK_INTERVAL = Duration.ofDays(1);
    private static final Duration HEALTHY_CHECK_INTERVAL = Duration.ofHours(1);

    /**
     * Creates the health monitor with a WebClient for outbound checks.
     *
     * @param webClientBuilder WebClient builder for outbound checks
     */
    public ExternalServiceHealth(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Seeds known services and performs the initial health checks.
     */
    @PostConstruct
    public void init() {
        // Initialize service statuses
        serviceStatuses.put(SERVICE_QDRANT, new ServiceStatus(SERVICE_QDRANT));

        // Perform initial health checks
        checkQdrantHealth();

        log.info("ExternalServiceHealth initialized, monitoring {} services", serviceStatuses.size());
    }

    /**
     * Checks whether a service is currently healthy and available for use.
     *
     * @param serviceName logical service name (for example, {@link #SERVICE_QDRANT})
     * @return true when the service is healthy or unknown, false when in backoff
     */
    public boolean isHealthy(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status == null) {
            return true; // Unknown services are assumed healthy
        }

        // If the service is healthy, return true
        if (status.isHealthy.get()) {
            return true;
        }

        // If unhealthy, check if we should retry based on backoff
        Instant nextCheck = status.lastCheck.plus(status.currentBackoff);
        if (Instant.now().isAfter(nextCheck)) {
            // Time to retry - trigger async health check
            if (SERVICE_QDRANT.equals(serviceName)) {
                checkQdrantHealthAsync();
            }
        }

        return false;
    }

    /**
     * Returns detailed status information for a service.
     *
     * @param serviceName logical service name
     * @return service status details for UI/diagnostics
     */
    public ServiceInfo getServiceInfo(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status == null) {
            return new ServiceInfo(serviceName, true, "Unknown service", null);
        }

        String message;
        Duration timeUntilNextCheck = null;

        if (status.isHealthy.get()) {
            message = String.format("Healthy (checked %s ago)",
                formatDuration(Duration.between(status.lastCheck, Instant.now())));
        } else {
            timeUntilNextCheck = Duration.between(Instant.now(),
                status.lastCheck.plus(status.currentBackoff));

            if (timeUntilNextCheck.isNegative()) {
                message = "Unhealthy (checking now...)";
                timeUntilNextCheck = Duration.ZERO;
            } else {
                message = String.format("Unhealthy (failed %d times, next check in %s)",
                    status.consecutiveFailures.get(), formatDuration(timeUntilNextCheck));
            }
        }

        return new ServiceInfo(serviceName, status.isHealthy.get(), message, timeUntilNextCheck);
    }

    /**
     * Scheduled health check for Qdrant (runs every hour for healthy services).
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    public void scheduledQdrantHealthCheck() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status != null && status.isHealthy.get()) {
            checkQdrantHealth();
        }
    }

    private void checkQdrantHealthAsync() {
        checkQdrantHealth();
    }

    private void checkQdrantHealth() {
        ServiceStatus status = serviceStatuses.get(SERVICE_QDRANT);
        if (status == null) return;

        String protocol = qdrantSsl ? "https" : "http";
        String healthUrl;

        if (qdrantSsl && qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            // For Qdrant Cloud, check collections endpoint instead of /health (which returns 403)
            healthUrl = String.format("%s://%s/collections", protocol, qdrantHost);
        } else {
            // For local instances, map gRPC port to REST port
            int restPort = mapGrpcPortToRestPort(qdrantPort);
            healthUrl = String.format("%s://%s:%d/health", protocol, qdrantHost, restPort);
        }

        var requestSpec = webClient.get().uri(healthUrl);

        // Add API key for cloud instances
        if (qdrantSsl && qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            requestSpec = requestSpec.header("api-key", qdrantApiKey);
        }

        requestSpec
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofSeconds(5))
            .doOnSuccess(response -> {
                status.markHealthy();
                log.debug("Qdrant health check succeeded");
            })
            .doOnError(error -> {
                status.markUnhealthy();
                log.warn("Qdrant health check failed (exception type: {}) - Will retry in {}",
                    error.getClass().getSimpleName(), formatDuration(status.currentBackoff));
            })
            .onErrorResume(error -> Mono.empty())
            .subscribe();
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
     * Maps gRPC port to corresponding REST port for Qdrant.
     *
     * <p>Qdrant exposes gRPC on one port and REST on another. The configured port
     * is typically gRPC; this method returns the corresponding REST port.
     *
     * @param grpcPort the configured gRPC port
     * @return the corresponding REST port
     */
    private int mapGrpcPortToRestPort(int grpcPort) {
        if (grpcPort == QDRANT_GRPC_PORT) {
            return QDRANT_REST_PORT; // 6334 -> 6333
        } else if (grpcPort == DOCKER_GRPC_PORT) {
            return DOCKER_REST_PORT; // 8086 -> 8087
        }
        // Assume caller configured the REST port directly
        return grpcPort;
    }

    /**
     * Internal class to track service status with exponential backoff
     */
    private static class ServiceStatus {
        final AtomicBoolean isHealthy = new AtomicBoolean(false);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile Instant lastCheck = Instant.now();
        volatile Duration currentBackoff = INITIAL_CHECK_INTERVAL;

        ServiceStatus(String name) {
            // Name parameter kept for future use if needed
        }

        void markHealthy() {
            isHealthy.set(true);
            consecutiveFailures.set(0);
            currentBackoff = HEALTHY_CHECK_INTERVAL;
            lastCheck = Instant.now();
        }

        void markUnhealthy() {
            isHealthy.set(false);
            int failures = consecutiveFailures.incrementAndGet();

            // Exponential backoff: 1min, 2min, 4min, 8min, ..., max 1 day
            Duration newBackoff = INITIAL_CHECK_INTERVAL.multipliedBy((long) Math.pow(2, failures - 1));
            if (newBackoff.compareTo(MAX_CHECK_INTERVAL) > 0) {
                newBackoff = MAX_CHECK_INTERVAL;
            }

            currentBackoff = newBackoff;
            lastCheck = Instant.now();
        }

        void reset() {
            isHealthy.set(false);
            consecutiveFailures.set(0);
            currentBackoff = INITIAL_CHECK_INTERVAL;
            lastCheck = Instant.EPOCH; // Force immediate check
        }
    }

    /**
     * Public DTO for service health information
     */
    public static class ServiceInfo {
        private final String name;
        private final boolean healthy;
        private final String message;
        private final Duration timeUntilNextCheck;

        /**
         * Creates a snapshot of service health status.
         */
        public ServiceInfo(String name, boolean isHealthy, String message, Duration timeUntilNextCheck) {
            this.name = name;
            this.healthy = isHealthy;
            this.message = message;
            this.timeUntilNextCheck = timeUntilNextCheck;
        }

        /**
         * Returns the service identifier.
         */
        public String name() {
            return name;
        }

        /**
         * Indicates whether the service is currently healthy.
         */
        public boolean isHealthy() {
            return healthy;
        }

        /**
         * Describes the current health state in human-readable form.
         */
        public String message() {
            return message;
        }

        /**
         * Returns the time until the next scheduled check, if applicable.
         */
        public Duration timeUntilNextCheck() {
            return timeUntilNextCheck;
        }
    }
}
