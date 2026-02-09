package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies retry-gate and backoff behavior in external service health tracking.
 */
class ExternalServiceHealthTest {

    @Test
    void serviceStatus_marksUnhealthyWithoutDurationOverflow() throws Exception {
        Object serviceStatus = newServiceStatus();
        AtomicInteger consecutiveFailures = readConsecutiveFailures(serviceStatus);
        consecutiveFailures.set(62);

        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);
        markUnhealthyMethod.invoke(serviceStatus);

        Field currentBackoffField = serviceStatus.getClass().getDeclaredField("currentBackoff");
        currentBackoffField.setAccessible(true);
        Duration currentBackoff = (Duration) currentBackoffField.get(serviceStatus);
        assertEquals(Duration.ofDays(1), currentBackoff);
    }

    @Test
    void serviceStatus_allowsOnlyOneConcurrentCheckUntilCompletion() throws Exception {
        Object serviceStatus = newServiceStatus();
        Method tryStartCheckMethod = serviceStatus.getClass().getDeclaredMethod("tryStartCheck", Instant.class);
        tryStartCheckMethod.setAccessible(true);

        Instant startInstant = Instant.now();
        boolean firstCheckStarted = (boolean) tryStartCheckMethod.invoke(serviceStatus, startInstant);
        boolean secondCheckStarted = (boolean) tryStartCheckMethod.invoke(serviceStatus, startInstant.plusSeconds(1));

        assertTrue(firstCheckStarted);
        assertFalse(secondCheckStarted);

        Method markHealthyMethod = serviceStatus.getClass().getDeclaredMethod("markHealthy");
        markHealthyMethod.setAccessible(true);
        markHealthyMethod.invoke(serviceStatus);

        boolean checkStartedAfterCompletion =
                (boolean) tryStartCheckMethod.invoke(serviceStatus, startInstant.plusSeconds(2));
        assertTrue(checkStartedAfterCompletion);
    }

    private Object newServiceStatus() throws Exception {
        Class<?> serviceStatusClass =
                Class.forName("com.williamcallahan.javachat.service.ExternalServiceHealth$ServiceStatus");
        Constructor<?> constructor = serviceStatusClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ExternalServiceHealth.SERVICE_QDRANT);
    }

    private AtomicInteger readConsecutiveFailures(Object serviceStatus) throws Exception {
        Field consecutiveFailuresField = serviceStatus.getClass().getDeclaredField("consecutiveFailures");
        consecutiveFailuresField.setAccessible(true);
        return (AtomicInteger) consecutiveFailuresField.get(serviceStatus);
    }
}
