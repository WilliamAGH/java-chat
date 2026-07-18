package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantConnectionProperties;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Verifies retry-gate, generation, and backoff behavior in external service health tracking.
 */
class ExternalServiceHealthTest {

    private static final int HEALTH_BACKOFF_OVERFLOW_FAILURE_COUNT = 63;
    private static final int QDRANT_GRPC_PORT = 6334;
    private static final Duration HEALTH_SNAPSHOT_CHECKING_OFFSET = Duration.ofMinutes(2);
    private static final String HEALTH_CHECK_IN_PROGRESS_MESSAGE = "Unhealthy (checking now...)";

    @Test
    void computeBackoffDurationCapsBeforeDurationOverflow() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();

        for (int failureIndex = 0; failureIndex < HEALTH_BACKOFF_OVERFLOW_FAILURE_COUNT; failureIndex++) {
            ExternalServiceHealth.HealthCheckToken checkToken =
                    serviceStatus.startCheck(Instant.now()).orElseThrow();
            assertTrue(serviceStatus.markUnhealthy(checkToken));
        }

        assertEquals(Duration.ofDays(1), serviceStatus.currentBackoff());
    }

    @Test
    void healthSnapshotKeepsMessageAlignedWithPublishedHealthState() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();

        ExternalServiceHealth.HealthCheckToken healthyCheck =
                serviceStatus.startCheck(Instant.now()).orElseThrow();
        assertTrue(serviceStatus.markHealthy(healthyCheck));
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        ExternalServiceHealth.HealthCheckToken unhealthyCheck =
                serviceStatus.startCheck(Instant.now()).orElseThrow();
        assertTrue(serviceStatus.markUnhealthy(unhealthyCheck));
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        serviceStatus.reset();
        assertHealthMessageAgreement(readHealthSnapshot(serviceStatus, Instant.now()));

        ExternalServiceHealth.HealthCheckToken activeCheck =
                serviceStatus.startCheck(Instant.now()).orElseThrow();
        ExternalServiceHealth.HealthSnapshot activeCheckSnapshot = readHealthSnapshot(serviceStatus, Instant.now());
        assertEquals(HEALTH_CHECK_IN_PROGRESS_MESSAGE, activeCheckSnapshot.message());
        assertEquals(Optional.of(Duration.ZERO), activeCheckSnapshot.timeUntilNextCheck());
        assertHealthMessageAgreement(activeCheckSnapshot);
        assertHealthMessageAgreement(
                readHealthSnapshot(serviceStatus, Instant.now().plus(HEALTH_SNAPSHOT_CHECKING_OFFSET)));
        assertTrue(serviceStatus.markHealthy(activeCheck));
    }

    @Test
    void serviceStatusAllowsOnlyOneConcurrentCheckUntilCompletion() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();
        Instant startInstant = Instant.now();
        ExternalServiceHealth.HealthCheckToken firstCheck =
                serviceStatus.startCheck(startInstant).orElseThrow();

        assertTrue(serviceStatus.startCheck(startInstant.plusSeconds(1)).isEmpty());
        assertTrue(serviceStatus.markHealthy(firstCheck));

        assertTrue(serviceStatus.startCheck(startInstant.plusSeconds(2)).isPresent());
    }

    @Test
    void resetRejectsCompletionFromPreviousGeneration() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();
        ExternalServiceHealth.HealthCheckToken staleCheck =
                serviceStatus.startCheck(Instant.now()).orElseThrow();

        serviceStatus.reset();
        ExternalServiceHealth.HealthCheckToken currentCheck =
                serviceStatus.startCheck(Instant.now()).orElseThrow();

        assertFalse(serviceStatus.markHealthy(staleCheck));
        assertTrue(serviceStatus.markUnhealthy(currentCheck));
        assertFalse(serviceStatus.isHealthy());
    }

    @Test
    void shouldRetryNowReturnsFalseWhenCheckInProgress() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();
        serviceStatus.startCheck(Instant.now()).orElseThrow();

        assertFalse(
                serviceStatus.shouldRetryNow(Instant.now().plusSeconds(3600)),
                "shouldRetryNow must return false while a check is in progress");
    }

    @Test
    void shouldRetryNowReturnsFalseBeforeBackoffElapses() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();
        markUnhealthy(serviceStatus);

        assertFalse(
                serviceStatus.shouldRetryNow(Instant.now().plusSeconds(30)),
                "shouldRetryNow must return false before backoff period elapses");
    }

    @Test
    void shouldRetryNowReturnsTrueAfterBackoffElapses() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();
        markUnhealthy(serviceStatus);

        assertTrue(
                serviceStatus.shouldRetryNow(Instant.now().plusSeconds(120)),
                "shouldRetryNow must return true after backoff period elapses");
    }

    @Test
    void computeBackoffDurationDoublesExponentiallyUntilCap() {
        ExternalServiceHealth.ServiceStatus serviceStatus = new ExternalServiceHealth.ServiceStatus();

        markUnhealthy(serviceStatus);
        assertEquals(Duration.ofMinutes(1), serviceStatus.currentBackoff());

        markUnhealthy(serviceStatus);
        assertEquals(Duration.ofMinutes(2), serviceStatus.currentBackoff());

        markUnhealthy(serviceStatus);
        assertEquals(Duration.ofMinutes(4), serviceStatus.currentBackoff());

        markUnhealthy(serviceStatus);
        markUnhealthy(serviceStatus);
        assertEquals(Duration.ofMinutes(16), serviceStatus.currentBackoff());
    }

    @Test
    void delayedConnectivityFailureCannotOverwriteStartupCollectionHealth() {
        DeferredConnectivityExchange deferredConnectivityExchange = new DeferredConnectivityExchange();
        ExternalServiceHealth externalServiceHealth = new ExternalServiceHealth(
                WebClient.builder().exchangeFunction(deferredConnectivityExchange),
                new QdrantRestConnection(new QdrantConnectionProperties("qdrant.test", QDRANT_GRPC_PORT, false, "")),
                new AppProperties());

        externalServiceHealth.init();
        assertTrue(deferredConnectivityExchange.hasPendingConnectivitySubscriber());

        externalServiceHealth.verifyQdrantCollectionsAfterStartup();
        assertTrue(externalServiceHealth.isHealthy(ExternalServiceHealth.SERVICE_QDRANT));

        deferredConnectivityExchange.failPendingConnectivityProbe();

        assertTrue(
                externalServiceHealth.isHealthy(ExternalServiceHealth.SERVICE_QDRANT),
                "a completion from the pre-reset connectivity probe must not overwrite current collection health");
    }

    private static void markUnhealthy(ExternalServiceHealth.ServiceStatus serviceStatus) {
        ExternalServiceHealth.HealthCheckToken checkToken =
                serviceStatus.startCheck(Instant.now()).orElseThrow();
        assertTrue(serviceStatus.markUnhealthy(checkToken));
    }

    private static ExternalServiceHealth.HealthSnapshot readHealthSnapshot(
            ExternalServiceHealth.ServiceStatus serviceStatus, Instant snapshotTime) {
        return serviceStatus.healthSnapshot(ExternalServiceHealth.SERVICE_QDRANT, snapshotTime);
    }

    private static void assertHealthMessageAgreement(ExternalServiceHealth.HealthSnapshot healthSnapshot) {
        assertEquals(healthSnapshot.isHealthy(), healthSnapshot.message().startsWith("Healthy"));
    }

    /**
     * Delays only the connectivity response so test order exactly models the startup race.
     */
    private static final class DeferredConnectivityExchange implements ExchangeFunction {
        private static final String CONNECTIVITY_PATH = "/health";
        private static final String COLLECTION_PATH_PREFIX = "/collections/";

        private final Sinks.One<ClientResponse> pendingConnectivityResponse = Sinks.one();

        @Override
        public Mono<ClientResponse> exchange(ClientRequest clientRequest) {
            String endpointPath = clientRequest.url().getPath();
            if (CONNECTIVITY_PATH.equals(endpointPath)) {
                return pendingConnectivityResponse.asMono();
            }
            if (endpointPath.startsWith(COLLECTION_PATH_PREFIX)) {
                return Mono.just(ClientResponse.create(HttpStatus.OK).build());
            }
            return Mono.error(new IllegalArgumentException("Unexpected Qdrant health endpoint " + endpointPath));
        }

        boolean hasPendingConnectivitySubscriber() {
            return pendingConnectivityResponse.currentSubscriberCount() == 1;
        }

        void failPendingConnectivityProbe() {
            assertTrue(pendingConnectivityResponse
                    .tryEmitError(new IllegalStateException("delayed connectivity failure"))
                    .isSuccess());
        }
    }
}
