package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.ExternalServiceHealth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Spring Actuator health indicator for Qdrant vector store.
 *
 * <p>Integrates with the existing {@link ExternalServiceHealth} service to expose
 * Qdrant status through the standard {@code /actuator/health} endpoint.
 */
@Component
public class QdrantHealthIndicator implements HealthIndicator {

    /** Health detail key for human-readable status message. */
    private static final String DETAIL_KEY_STATUS = "status";
    /** Health detail key for time until next health check attempt. */
    private static final String DETAIL_KEY_NEXT_CHECK_IN = "nextCheckIn";

    private final ExternalServiceHealth externalServiceHealth;
    private final ObjectProvider<QdrantIndexInitializer> indexInitializerProvider;
    private final ObjectProvider<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscoveryProvider;

    /**
     * Creates the health indicator with a reference to the external service health monitor.
     *
     * @param externalServiceHealth the service health monitor
     * @param indexInitializerProvider provides initialization state outside the test profile
     * @param gitHubCollectionDiscoveryProvider provides governed GitHub discovery readiness
     */
    public QdrantHealthIndicator(
            ExternalServiceHealth externalServiceHealth,
            ObjectProvider<QdrantIndexInitializer> indexInitializerProvider,
            ObjectProvider<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscoveryProvider) {
        this.externalServiceHealth = externalServiceHealth;
        this.indexInitializerProvider = indexInitializerProvider;
        this.gitHubCollectionDiscoveryProvider = gitHubCollectionDiscoveryProvider;
    }

    /**
     * Checks Qdrant health by delegating to the centralized external service monitor.
     *
     * <p>When healthy, returns UP with a status message. When unhealthy, returns DOWN
     * with status details and optionally the time until the next retry attempt.
     *
     * @return Health.UP when Qdrant is reachable, Health.DOWN otherwise
     */
    @Override
    public Health health() {
        // Trigger retry checks when unhealthy and backoff has elapsed.
        externalServiceHealth.triggerRetryIfDue(ExternalServiceHealth.SERVICE_QDRANT);

        QdrantIndexInitializer indexInitializer = indexInitializerProvider.getIfAvailable();
        if (indexInitializer != null) {
            Health initializationHealth = indexInitializer.initializationHealth();
            if (!Status.UP.equals(initializationHealth.getStatus())) {
                return initializationHealth;
            }
        }

        QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery = gitHubCollectionDiscoveryProvider.getIfAvailable();
        if (gitHubCollectionDiscovery != null) {
            Health discoveryHealth = gitHubCollectionDiscovery.discoveryHealth();
            if (!Status.UP.equals(discoveryHealth.getStatus())) {
                return discoveryHealth;
            }
        }

        ExternalServiceHealth.HealthSnapshot healthSnapshot =
                externalServiceHealth.getHealthSnapshot(ExternalServiceHealth.SERVICE_QDRANT);

        if (healthSnapshot.isHealthy()) {
            return Health.up()
                    .withDetail(DETAIL_KEY_STATUS, healthSnapshot.message())
                    .build();
        }

        // Include time until next check for debugging
        Health.Builder builder = Health.down().withDetail(DETAIL_KEY_STATUS, healthSnapshot.message());

        healthSnapshot
                .timeUntilNextCheck()
                .ifPresent(duration -> builder.withDetail(DETAIL_KEY_NEXT_CHECK_IN, duration.toString()));

        return builder.build();
    }
}
