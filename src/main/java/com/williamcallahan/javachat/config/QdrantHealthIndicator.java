package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.ExternalServiceHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Actuator health indicator for Qdrant vector store.
 *
 * <p>Integrates with the existing {@link ExternalServiceHealth} service to expose
 * Qdrant status through the standard {@code /actuator/health} endpoint.
 */
@Component
public class QdrantHealthIndicator implements HealthIndicator {

    private final ExternalServiceHealth externalServiceHealth;

    /**
     * Creates the health indicator with a reference to the external service health monitor.
     *
     * @param externalServiceHealth the service health monitor
     */
    public QdrantHealthIndicator(ExternalServiceHealth externalServiceHealth) {
        this.externalServiceHealth = externalServiceHealth;
    }

    @Override
    public Health health() {
        ExternalServiceHealth.HealthSnapshot healthSnapshot =
                externalServiceHealth.getHealthSnapshot(ExternalServiceHealth.SERVICE_QDRANT);

        if (healthSnapshot.isHealthy()) {
            return Health.up().withDetail("status", healthSnapshot.message()).build();
        }

        // Include time until next check for debugging
        Health.Builder builder = Health.down().withDetail("status", healthSnapshot.message());

        healthSnapshot
                .timeUntilNextCheck()
                .ifPresent(duration -> builder.withDetail("nextCheckIn", duration.toString()));

        return builder.build();
    }
}
