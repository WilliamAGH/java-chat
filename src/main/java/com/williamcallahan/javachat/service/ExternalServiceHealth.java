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
    
    private final WebClient webClient;
    private final Map<String, ServiceStatus> serviceStatuses = new ConcurrentHashMap<>();
    
    @Value("${QDRANT_HOST:localhost}")
    private String qdrantHost;
    
    @Value("${QDRANT_PORT:6334}")
    private int qdrantPort;
    
    @Value("${QDRANT_SSL:false}")
    private boolean qdrantSsl;
    
    @Value("${QDRANT_API_KEY:}")
    private String qdrantApiKey;
    
    @Value("${QDRANT_COLLECTION:java-chat}")
    private String qdrantCollection;
    
    // Health check intervals
    private static final Duration INITIAL_CHECK_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_CHECK_INTERVAL = Duration.ofDays(1);
    private static final Duration HEALTHY_CHECK_INTERVAL = Duration.ofHours(1);
    
    /**
     * Creates the health monitor with a WebClient for outbound checks.
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
        serviceStatuses.put("qdrant", new ServiceStatus("qdrant"));
        
        // Perform initial health checks
        checkQdrantHealth();
        
        log.info("ExternalServiceHealth initialized, monitoring {} services", serviceStatuses.size());
    }
    
    /**
     * Check if a service is currently healthy and available for use
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
            if ("qdrant".equals(serviceName)) {
                checkQdrantHealthAsync();
            }
        }
        
        return false;
    }
    
    /**
     * Get detailed status for a service
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
     * Scheduled health check for Qdrant (runs every hour for healthy services)
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    public void scheduledQdrantHealthCheck() {
        ServiceStatus status = serviceStatuses.get("qdrant");
        if (status != null && status.isHealthy.get()) {
            checkQdrantHealth();
        }
    }
    
    private void checkQdrantHealthAsync() {
        checkQdrantHealth();
    }
    
    private void checkQdrantHealth() {
        ServiceStatus status = serviceStatuses.get("qdrant");
        if (status == null) return;
        
        String protocol = qdrantSsl ? "https" : "http";
        String healthUrl;
        
        if (qdrantSsl && qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            // For Qdrant Cloud, check collections endpoint instead of /health (which returns 403)
            healthUrl = String.format("%s://%s/collections", protocol, qdrantHost);
        } else {
            // For local instances, use the health endpoint
            int restPort = (qdrantPort == 6334) ? 6333 : qdrantPort; // Convert gRPC port to REST port
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
                log.debug("Qdrant health check succeeded via {}", healthUrl);
            })
            .doOnError(error -> {
                status.markUnhealthy();
                log.warn("Qdrant health check failed: {} - Will retry in {}", 
                    error.getMessage(), formatDuration(status.currentBackoff));
            })
            .onErrorResume(error -> Mono.empty())
            .subscribe();
    }
    
    /**
     * Force a health check for a specific service
     */
    public void forceHealthCheck(String serviceName) {
        if ("qdrant".equals(serviceName)) {
            checkQdrantHealth();
        }
    }
    
    /**
     * Reset a service's health status (useful for manual intervention)
     */
    public void resetServiceStatus(String serviceName) {
        ServiceStatus status = serviceStatuses.get(serviceName);
        if (status != null) {
            status.reset();
            log.info("Reset health status for service: {}", serviceName);
            
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
