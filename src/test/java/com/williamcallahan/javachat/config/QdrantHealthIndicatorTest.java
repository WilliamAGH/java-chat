package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.ExternalServiceHealth;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Verifies that actuator health checks trigger retry evaluation in ExternalServiceHealth.
 */
class QdrantHealthIndicatorTest {

    @Test
    void health_triggersRetryEvaluationBeforeSnapshotRendering() {
        ExternalServiceHealth externalServiceHealth = mock(ExternalServiceHealth.class);
        QdrantHealthIndicator qdrantHealthIndicator = new QdrantHealthIndicator(externalServiceHealth);

        ExternalServiceHealth.HealthSnapshot unhealthySnapshot = new ExternalServiceHealth.HealthSnapshot(
                ExternalServiceHealth.SERVICE_QDRANT, false, "Unhealthy (checking now...)", Duration.ZERO);
        when(externalServiceHealth.getHealthSnapshot(ExternalServiceHealth.SERVICE_QDRANT))
                .thenReturn(unhealthySnapshot);

        Health health = qdrantHealthIndicator.health();

        verify(externalServiceHealth, times(1)).triggerRetryIfDue(ExternalServiceHealth.SERVICE_QDRANT);
        assertEquals(Status.DOWN, health.getStatus());
    }
}
