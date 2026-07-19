package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.ExternalServiceHealth;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Verifies that actuator health checks trigger retry evaluation in ExternalServiceHealth.
 */
class QdrantHealthIndicatorTest {

    @Test
    void health_triggersRetryEvaluationBeforeSnapshotRendering() {
        ExternalServiceHealth externalServiceHealth = mock(ExternalServiceHealth.class);
        QdrantHealthIndicator qdrantHealthIndicator = new QdrantHealthIndicator(
                externalServiceHealth, absentIndexInitializerProvider(), absentDiscoveryProvider());

        ExternalServiceHealth.HealthSnapshot unhealthySnapshot = new ExternalServiceHealth.HealthSnapshot(
                ExternalServiceHealth.SERVICE_QDRANT, false, "Unhealthy (checking now...)", Duration.ZERO);
        when(externalServiceHealth.getHealthSnapshot(ExternalServiceHealth.SERVICE_QDRANT))
                .thenReturn(unhealthySnapshot);

        Health health = qdrantHealthIndicator.health();

        verify(externalServiceHealth, times(1)).triggerRetryIfDue(ExternalServiceHealth.SERVICE_QDRANT);
        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void health_remainsDownUntilCollectionInitializationCompletes() {
        ExternalServiceHealth externalServiceHealth = mock(ExternalServiceHealth.class);
        QdrantIndexInitializer indexInitializer = mock(QdrantIndexInitializer.class);
        when(indexInitializer.initializationHealth())
                .thenReturn(
                        Health.down().withDetail("initialization", "pending").build());
        QdrantHealthIndicator qdrantHealthIndicator = new QdrantHealthIndicator(
                externalServiceHealth, indexInitializerProvider(indexInitializer), absentDiscoveryProvider());

        Health health = qdrantHealthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("pending", health.getDetails().get("initialization"));
        verify(externalServiceHealth, times(1)).triggerRetryIfDue(ExternalServiceHealth.SERVICE_QDRANT);
    }

    @Test
    void healthRemainsDownWhenGovernedGitHubDiscoveryFails() {
        ExternalServiceHealth externalServiceHealth = mock(ExternalServiceHealth.class);
        QdrantGitHubCollectionDiscovery discovery = mock(QdrantGitHubCollectionDiscovery.class);
        when(discovery.discoveryHealth())
                .thenReturn(Health.down()
                        .withDetail("githubCollectionDiscovery", "failed")
                        .build());
        QdrantHealthIndicator qdrantHealthIndicator = new QdrantHealthIndicator(
                externalServiceHealth, absentIndexInitializerProvider(), discoveryProvider(discovery));

        Health health = qdrantHealthIndicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("failed", health.getDetails().get("githubCollectionDiscovery"));
    }

    private ObjectProvider<QdrantIndexInitializer> absentIndexInitializerProvider() {
        return new ObjectProvider<>() {
            @Override
            public QdrantIndexInitializer getIfAvailable() {
                return null;
            }
        };
    }

    private ObjectProvider<QdrantIndexInitializer> indexInitializerProvider(QdrantIndexInitializer indexInitializer) {
        return new ObjectProvider<>() {
            @Override
            public QdrantIndexInitializer getIfAvailable() {
                return indexInitializer;
            }
        };
    }

    private ObjectProvider<QdrantGitHubCollectionDiscovery> absentDiscoveryProvider() {
        return new ObjectProvider<>() {
            @Override
            public QdrantGitHubCollectionDiscovery getIfAvailable() {
                return null;
            }
        };
    }

    private ObjectProvider<QdrantGitHubCollectionDiscovery> discoveryProvider(
            QdrantGitHubCollectionDiscovery discovery) {
        return new ObjectProvider<>() {
            @Override
            public QdrantGitHubCollectionDiscovery getIfAvailable() {
                return discovery;
            }
        };
    }
}
