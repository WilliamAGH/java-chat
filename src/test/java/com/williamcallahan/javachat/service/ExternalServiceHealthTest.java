package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies retry-gate and backoff behavior in external service health tracking.
 */
class ExternalServiceHealthTest {

    private static final int HEALTH_BACKOFF_OVERFLOW_FAILURE_COUNT = 63;
    private static final Duration HEALTH_SNAPSHOT_CHECKING_OFFSET = Duration.ofMinutes(2);
    private static final String HEALTH_CHECK_IN_PROGRESS_MESSAGE = "Unhealthy (checking now...)";

    @Test
    void computeBackoffDuration_capsBeforeDurationOverflow() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method computeBackoffDurationMethod =
                serviceStatus.getClass().getDeclaredMethod("computeBackoffDuration", int.class);
        computeBackoffDurationMethod.setAccessible(true);

        Duration currentBackoff =
                (Duration) computeBackoffDurationMethod.invoke(serviceStatus, HEALTH_BACKOFF_OVERFLOW_FAILURE_COUNT);
        assertEquals(Duration.ofDays(1), currentBackoff);
    }

    @Test
    void healthSnapshot_keepsMessageAlignedWithPublishedHealthState() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method markHealthyMethod = serviceStatus.getClass().getDeclaredMethod("markHealthy");
        markHealthyMethod.setAccessible(true);
        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);
        Method resetMethod = serviceStatus.getClass().getDeclaredMethod("reset");
        resetMethod.setAccessible(true);
        Method tryStartCheckMethod = serviceStatus.getClass().getDeclaredMethod("tryStartCheck", Instant.class);
        tryStartCheckMethod.setAccessible(true);

        markHealthyMethod.invoke(serviceStatus);
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        markUnhealthyMethod.invoke(serviceStatus);
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        resetMethod.invoke(serviceStatus);
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        boolean checkStarted = (boolean) tryStartCheckMethod.invoke(serviceStatus, Instant.now());
        assertTrue(checkStarted);
        ExternalServiceHealth.HealthSnapshot activeCheckSnapshot = readHealthSnapshot(serviceStatus, Instant.now());
        assertEquals(HEALTH_CHECK_IN_PROGRESS_MESSAGE, activeCheckSnapshot.message());
        assertEquals(Optional.of(Duration.ZERO), activeCheckSnapshot.timeUntilNextCheck());
        assertHealthMessageAgreement(activeCheckSnapshot);
        assertHealthMessageAgreement(
                readHealthSnapshot(serviceStatus, Instant.now().plus(HEALTH_SNAPSHOT_CHECKING_OFFSET)));
    }

    @Test
    void serviceStatus_allowsOnlyOneConcurrentCheckUntilCompletion() throws ReflectiveOperationException {
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

    @Test
    void shouldRetryNow_returnsFalseWhenCheckInProgress() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method tryStartCheckMethod = serviceStatus.getClass().getDeclaredMethod("tryStartCheck", Instant.class);
        tryStartCheckMethod.setAccessible(true);
        Method shouldRetryNowMethod = serviceStatus.getClass().getDeclaredMethod("shouldRetryNow", Instant.class);
        shouldRetryNowMethod.setAccessible(true);

        tryStartCheckMethod.invoke(serviceStatus, Instant.now());

        boolean shouldRetry = (boolean)
                shouldRetryNowMethod.invoke(serviceStatus, Instant.now().plusSeconds(3600));
        assertFalse(shouldRetry, "shouldRetryNow must return false while a check is in progress");
    }

    @Test
    void shouldRetryNow_returnsFalseBeforeBackoffElapses() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);
        Method shouldRetryNowMethod = serviceStatus.getClass().getDeclaredMethod("shouldRetryNow", Instant.class);
        shouldRetryNowMethod.setAccessible(true);

        markUnhealthyMethod.invoke(serviceStatus);

        Instant beforeBackoffElapses = Instant.now().plusSeconds(30);
        boolean shouldRetry = (boolean) shouldRetryNowMethod.invoke(serviceStatus, beforeBackoffElapses);
        assertFalse(shouldRetry, "shouldRetryNow must return false before backoff period elapses");
    }

    @Test
    void shouldRetryNow_returnsTrueAfterBackoffElapses() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);
        Method shouldRetryNowMethod = serviceStatus.getClass().getDeclaredMethod("shouldRetryNow", Instant.class);
        shouldRetryNowMethod.setAccessible(true);

        markUnhealthyMethod.invoke(serviceStatus);

        Instant afterBackoffElapses = Instant.now().plusSeconds(120);
        boolean shouldRetry = (boolean) shouldRetryNowMethod.invoke(serviceStatus, afterBackoffElapses);
        assertTrue(shouldRetry, "shouldRetryNow must return true after backoff period elapses");
    }

    @Test
    void computeBackoffDuration_doublesExponentiallyUntilCap() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);

        // First failure: 1 minute
        markUnhealthyMethod.invoke(serviceStatus);
        assertEquals(Duration.ofMinutes(1), readCurrentBackoff(serviceStatus));

        // Second failure: 2 minutes
        markUnhealthyMethod.invoke(serviceStatus);
        assertEquals(Duration.ofMinutes(2), readCurrentBackoff(serviceStatus));

        // Third failure: 4 minutes
        markUnhealthyMethod.invoke(serviceStatus);
        assertEquals(Duration.ofMinutes(4), readCurrentBackoff(serviceStatus));

        // Fifth failure (after fourth = 8m): 16 minutes
        markUnhealthyMethod.invoke(serviceStatus);
        markUnhealthyMethod.invoke(serviceStatus);
        assertEquals(Duration.ofMinutes(16), readCurrentBackoff(serviceStatus));
    }

    private Object newServiceStatus() throws ReflectiveOperationException {
        Class<?> serviceStatusClass =
                Class.forName("com.williamcallahan.javachat.service.ExternalServiceHealth$ServiceStatus");
        Constructor<?> constructor = serviceStatusClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private ExternalServiceHealth.HealthSnapshot readHealthSnapshot(Object serviceStatus, Instant snapshotTime)
            throws ReflectiveOperationException {
        Method healthSnapshotMethod =
                serviceStatus.getClass().getDeclaredMethod("healthSnapshot", String.class, Instant.class);
        healthSnapshotMethod.setAccessible(true);
        return (ExternalServiceHealth.HealthSnapshot)
                healthSnapshotMethod.invoke(serviceStatus, ExternalServiceHealth.SERVICE_QDRANT, snapshotTime);
    }

    private Duration readCurrentBackoff(Object serviceStatus) throws ReflectiveOperationException {
        Method currentBackoffMethod = serviceStatus.getClass().getDeclaredMethod("currentBackoff");
        currentBackoffMethod.setAccessible(true);
        return (Duration) currentBackoffMethod.invoke(serviceStatus);
    }

    private void assertHealthMessageAgreement(ExternalServiceHealth.HealthSnapshot healthSnapshot) {
        assertEquals(healthSnapshot.isHealthy(), healthSnapshot.message().startsWith("Healthy"));
    }
}
