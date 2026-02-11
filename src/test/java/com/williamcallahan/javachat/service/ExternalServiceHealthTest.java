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
    void serviceStatus_marksUnhealthyWithoutDurationOverflow() throws ReflectiveOperationException {
        Object serviceStatus = newServiceStatus();
        AtomicInteger consecutiveFailures = readConsecutiveFailures(serviceStatus);
        consecutiveFailures.set(62);

        Method markUnhealthyMethod = serviceStatus.getClass().getDeclaredMethod("markUnhealthy");
        markUnhealthyMethod.setAccessible(true);
        markUnhealthyMethod.invoke(serviceStatus);

        Duration currentBackoff = readCurrentBackoff(serviceStatus);
        assertEquals(Duration.ofDays(1), currentBackoff);
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

    @Test
    void buildQdrantRestBaseUrl_withTlsAndDefaultPort_includesHttpsAndMappedPort() throws ReflectiveOperationException {
        ExternalServiceHealth health = newExternalServiceHealth("cloud.qdrant.io", 6334, true);
        String restBaseUrl = invokeBuildQdrantRestBaseUrl(health);
        assertEquals("https://cloud.qdrant.io:6333", restBaseUrl);
    }

    @Test
    void buildQdrantRestBaseUrl_withoutTls_includesHttpAndMappedPort() throws ReflectiveOperationException {
        ExternalServiceHealth health = newExternalServiceHealth("localhost", 6334, false);
        String restBaseUrl = invokeBuildQdrantRestBaseUrl(health);
        assertEquals("http://localhost:6333", restBaseUrl);
    }

    @Test
    void buildQdrantRestBaseUrl_withDockerPort_mapsToDockerRestPort() throws ReflectiveOperationException {
        ExternalServiceHealth health = newExternalServiceHealth("localhost", 8086, false);
        String restBaseUrl = invokeBuildQdrantRestBaseUrl(health);
        assertEquals("http://localhost:8087", restBaseUrl);
    }

    @Test
    void buildQdrantRestBaseUrl_withTlsAndDockerPort_mapsToDockerRestPort() throws ReflectiveOperationException {
        ExternalServiceHealth health = newExternalServiceHealth("cloud.qdrant.io", 8086, true);
        String restBaseUrl = invokeBuildQdrantRestBaseUrl(health);
        assertEquals("https://cloud.qdrant.io:8087", restBaseUrl);
    }

    private ExternalServiceHealth newExternalServiceHealth(String host, int port, boolean ssl)
            throws ReflectiveOperationException {
        // Use a minimal constructor — WebClient.Builder and AppProperties are needed
        // but buildQdrantRestBaseUrl only reads injected @Value fields, so we set them via reflection.
        var builder = org.springframework.web.reactive.function.client.WebClient.builder();
        var appProperties = new com.williamcallahan.javachat.config.AppProperties();
        var collections = new com.williamcallahan.javachat.config.AppProperties.QdrantCollections();
        collections.setBooks("books");
        collections.setDocs("docs");
        collections.setArticles("articles");
        collections.setPdfs("pdfs");
        var qdrant = appProperties.getQdrant();
        qdrant.setCollections(collections);

        ExternalServiceHealth health = new ExternalServiceHealth(builder, appProperties);
        setField(health, "qdrantHost", host);
        setField(health, "qdrantPort", port);
        setField(health, "qdrantSsl", ssl);
        return health;
    }

    private String invokeBuildQdrantRestBaseUrl(ExternalServiceHealth health) throws ReflectiveOperationException {
        Method method = ExternalServiceHealth.class.getDeclaredMethod("buildQdrantRestBaseUrl");
        method.setAccessible(true);
        return (String) method.invoke(health);
    }

    private void setField(Object target, String fieldName, Object fieldValue) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, fieldValue);
    }

    private Object newServiceStatus() throws ReflectiveOperationException {
        Class<?> serviceStatusClass =
                Class.forName("com.williamcallahan.javachat.service.ExternalServiceHealth$ServiceStatus");
        Constructor<?> constructor = serviceStatusClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ExternalServiceHealth.SERVICE_QDRANT);
    }

    private AtomicInteger readConsecutiveFailures(Object serviceStatus) throws ReflectiveOperationException {
        Field consecutiveFailuresField = serviceStatus.getClass().getDeclaredField("consecutiveFailures");
        consecutiveFailuresField.setAccessible(true);
        return (AtomicInteger) consecutiveFailuresField.get(serviceStatus);
    }

    private Duration readCurrentBackoff(Object serviceStatus) throws ReflectiveOperationException {
        Field currentBackoffField = serviceStatus.getClass().getDeclaredField("currentBackoff");
        currentBackoffField.setAccessible(true);
        return (Duration) currentBackoffField.get(serviceStatus);
    }
}
