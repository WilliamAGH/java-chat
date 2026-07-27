package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.ConfiguredProviderBackoff;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.grpc.Status;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;

/**
 * Verifies configured-provider validation, backoff, and failure classification policy.
 *
 * <p>This suite targets routing directly so streaming transport tests remain focused on stream behavior.</p>
 */
class OpenAiProviderRoutingServiceTest {
    private static final long CONFIGURED_PROVIDER_BACKOFF_SECONDS = 600L;
    private static final int CONCURRENT_FAILURE_RECORDERS = 2;
    private static final int CONCURRENT_TEST_TIMEOUT_SECONDS = 10;
    private static final Duration CAUSE_CLASSIFICATION_TIMEOUT = Duration.ofSeconds(1);
    private static final String OPENAI_REQUEST_FAILED_MESSAGE = "Request failed";
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";
    private static final String CALLER_INTERRUPTION_MESSAGE = "request interrupted by caller timeout";
    private static final Instant CONFIGURED_PROVIDER_BACKOFF_START = Instant.parse("2026-07-18T00:00:00Z");
    private static final Logger PROVIDER_ROUTING_LOGGER =
            (Logger) LoggerFactory.getLogger(OpenAiProviderRoutingService.class);

    private OpenAiProviderRoutingService createRoutingService() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        return new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties());
    }

    @Test
    void shouldBackoffConfiguredProviderTreatsSdkIoAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.shouldBackoffConfiguredProvider(new OpenAIIoException("io")));
    }

    @Test
    void shouldBackoffConfiguredProviderIgnoresWrappedCallerInterruption() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.shouldBackoffConfiguredProvider(wrappedCallerInterruption()));
    }

    @Test
    void shouldBackoffConfiguredProviderTreatsWrappedOkHttpCallTimeoutAsBackoffEligible() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.shouldBackoffConfiguredProvider(wrappedOkHttpCallTimeout()));
    }

    @Test
    void recoverableStreamingFailureTreatsWrappedOkHttpCallTimeoutAsRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.isRecoverableStreamingFailure(wrappedOkHttpCallTimeout()));
    }

    @Test
    void callerCancellationKeepsConfiguredProviderEligible() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties());
        OpenAIClient openAiClient = mock(OpenAIClient.class);

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, wrappedCallerInterruption());

        OpenAiProviderCandidate configuredProvider =
                routingService.admitConfiguredProviderRequest(openAiClient).orElseThrow();
        assertEquals(RateLimitService.ApiProvider.OPENAI, configuredProvider.provider());
    }

    @Test
    void permanentProviderFailuresAndWrappersDoNotCreateRetryableCooldown() {
        Headers headers = Headers.builder().build();
        List<RuntimeException> permanentProviderFailures = List.of(
                UnauthorizedException.builder().headers(headers).build(),
                PermissionDeniedException.builder().headers(headers).build(),
                NotFoundException.builder().headers(headers).build(),
                new OpenAIIoException(
                        OPENAI_REQUEST_FAILED_MESSAGE,
                        UnauthorizedException.builder().headers(headers).build()),
                new OpenAIIoException(
                        OPENAI_REQUEST_FAILED_MESSAGE,
                        PermissionDeniedException.builder().headers(headers).build()),
                new OpenAIIoException(
                        OPENAI_REQUEST_FAILED_MESSAGE,
                        NotFoundException.builder().headers(headers).build()));

        for (RuntimeException permanentProviderFailure : permanentProviderFailures) {
            RateLimitService rateLimitService = mock(RateLimitService.class);
            when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                    .thenReturn(true);
            OpenAiProviderRoutingService routingService = createRoutingService(rateLimitService);
            OpenAIClient configuredClient = mock(OpenAIClient.class);

            routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, permanentProviderFailure);

            OpenAiProviderCandidate providerAdmission = routingService
                    .admitConfiguredProviderRequest(configuredClient)
                    .orElseThrow();
            assertEquals(RateLimitService.ApiProvider.OPENAI, providerAdmission.provider());
            assertFalse(routingService.shouldBackoffConfiguredProvider(permanentProviderFailure));
            assertFalse(routingService.isRecoverableStreamingFailure(permanentProviderFailure));
        }
    }

    @Test
    void rateLimitFailureUsesRateLimitServiceWithoutStartingFixedCooldown() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService = createRoutingService(rateLimitService);
        RateLimitException rateLimitFailure =
                RateLimitException.builder().headers(Headers.builder().build()).build();
        OpenAIClient configuredClient = mock(OpenAIClient.class);

        assertFalse(routingService.shouldBackoffConfiguredProvider(rateLimitFailure));

        routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, rateLimitFailure);

        verify(rateLimitService)
                .recordRateLimitFromOpenAiServiceException(RateLimitService.ApiProvider.OPENAI, rateLimitFailure);
        OpenAiProviderCandidate providerAdmission =
                routingService.admitConfiguredProviderRequest(configuredClient).orElseThrow();
        assertEquals(RateLimitService.ApiProvider.OPENAI, providerAdmission.provider());
    }

    @Test
    void shouldBackoffConfiguredProviderDoesNotBackoffOnGenericRuntime() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.shouldBackoffConfiguredProvider(new IllegalArgumentException("no")));
    }

    @Test
    void cyclicCauseClassificationTerminatesForGenericFailure() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        IllegalStateException cycleEntryFailure = new IllegalStateException("cycle entry");
        IllegalStateException cycleLinkFailure = new IllegalStateException("cycle link", cycleEntryFailure);
        cycleEntryFailure.initCause(cycleLinkFailure);

        assertTimeoutPreemptively(CAUSE_CLASSIFICATION_TIMEOUT, () -> {
            assertFalse(routingService.shouldBackoffConfiguredProvider(cycleEntryFailure));
            assertFalse(routingService.isRecoverableStreamingFailure(cycleEntryFailure));
        });
    }

    @Test
    void recoverableStreamingFailureTreatsReactorOverflowTypeAsRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertTrue(routingService.isRecoverableStreamingFailure(Exceptions.failWithOverflow()));
    }

    @Test
    void recoverableStreamingFailureTreatsValidationErrorsAsNonRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();

        assertFalse(routingService.isRecoverableStreamingFailure(new IllegalArgumentException("bad request payload")));
        assertFalse(routingService.isRecoverableStreamingFailure(
                new EmbeddingServiceUnavailableException("embedding dimension mismatch")));
    }

    @Test
    void recoverableStreamingFailureUsesTypedEmbeddingAndQdrantDisposition() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        HybridSearchPartialFailureException transientRetrievalFailure = new HybridSearchPartialFailureException(
                "temporary Qdrant failure",
                List.of(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-docs",
                        "StatusRuntimeException",
                        "unavailable",
                        HybridSearchPartialFailureException.classifyDependencyFailure(
                                Status.UNAVAILABLE.asRuntimeException()))));
        HybridSearchPartialFailureException permanentRetrievalFailure = new HybridSearchPartialFailureException(
                "permanent Qdrant failure",
                List.of(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        "java-docs",
                        "StatusRuntimeException",
                        "permission denied",
                        HybridSearchPartialFailureException.classifyDependencyFailure(
                                Status.PERMISSION_DENIED.asRuntimeException()))));

        assertTrue(routingService.isRecoverableStreamingFailure(
                new EmbeddingServiceTemporarilyUnavailableException("embedding cooldown")));
        assertTrue(routingService.isRecoverableStreamingFailure(transientRetrievalFailure));
        assertFalse(routingService.isRecoverableStreamingFailure(permanentRetrievalFailure));
    }

    @Test
    void recoverableStreamingFailureTreatsNotFoundAsNonRetryable() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();
        NotFoundException notFoundException =
                NotFoundException.builder().headers(headers).build();

        assertFalse(routingService.isRecoverableStreamingFailure(notFoundException));
    }

    @Test
    void recoverableStreamingFailureRejectsAuthenticationAuthorizationAndRateLimitFailures() {
        OpenAiProviderRoutingService routingService = createRoutingService();
        Headers headers = Headers.builder().build();

        assertFalse(routingService.isRecoverableStreamingFailure(
                UnauthorizedException.builder().headers(headers).build()));
        assertFalse(routingService.isRecoverableStreamingFailure(
                PermissionDeniedException.builder().headers(headers).build()));
        assertFalse(routingService.isRecoverableStreamingFailure(
                RateLimitException.builder().headers(headers).build()));
    }

    @Test
    void gatewayTimeoutBackoffMakesNextConfiguredProviderRequestRetryable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties());
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        InternalServerException gatewayTimeout = InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();

        ConfiguredProviderTemporarilyUnavailableException temporaryFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeout);

            temporaryFailure = assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(openAiClient));

            assertWarningMessages(
                    expectedLogEvents,
                    List.of(
                            "Configured provider temporarily disabled for 600s due to failure",
                            "Configured provider unavailable (backoff active, providerId=0)"));
        }

        assertEquals(RateLimitService.ApiProvider.OPENAI, temporaryFailure.provider());
        assertTrue(routingService.isRecoverableStreamingFailure(temporaryFailure));
    }

    @Test
    void configuredProviderBackoffDoesNotRouteToAlternateProvider() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties());
        OpenAIClient openAiClient = mock(OpenAIClient.class);

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, new OpenAIIoException("io"));

            assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(openAiClient));

            assertWarningMessages(
                    expectedLogEvents,
                    List.of(
                            "Configured provider temporarily disabled for 600s due to failure",
                            "Configured provider unavailable (backoff active, providerId=0)"));
        }
    }

    @Test
    void rateLimitAdmissionDenialIsRetryable() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(false);
        OpenAiProviderRoutingService routingService = createRoutingService(rateLimitService);
        OpenAIClient configuredClient = mock(OpenAIClient.class);

        ConfiguredProviderTemporarilyUnavailableException temporaryFailure;
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            temporaryFailure = assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(configuredClient));
            assertWarningMessages(
                    expectedLogEvents,
                    List.of("Configured provider admission denied (providerId="
                            + RateLimitService.ApiProvider.OPENAI.ordinal() + ")"));
        }

        assertEquals(RateLimitService.ApiProvider.OPENAI, temporaryFailure.provider());
        assertTrue(routingService.isRecoverableStreamingFailure(temporaryFailure));
    }

    @Test
    void configuredProviderCooldownExpiresAtItsExactDeadline() {
        AtomicReference<Instant> currentTime = new AtomicReference<>(CONFIGURED_PROVIDER_BACKOFF_START);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties(), currentTime::get);
        OpenAIClient configuredClient = mock(OpenAIClient.class);

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeoutFailure());
            currentTime.set(CONFIGURED_PROVIDER_BACKOFF_START
                    .plusSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS)
                    .minusNanos(1));

            assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(configuredClient));

            currentTime.set(CONFIGURED_PROVIDER_BACKOFF_START.plusSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS));

            assertDoesNotThrow(() -> routingService.admitConfiguredProviderRequest(configuredClient));
            assertWarningMessages(
                    expectedLogEvents,
                    List.of(
                            "Configured provider temporarily disabled for 600s due to failure",
                            "Configured provider unavailable (backoff active, providerId="
                                    + RateLimitService.ApiProvider.OPENAI.ordinal() + ")"));
        }
    }

    @Test
    void olderInFlightSuccessDoesNotEraseNewerConfiguredProviderCooldown() {
        AtomicReference<Instant> currentTime = new AtomicReference<>(CONFIGURED_PROVIDER_BACKOFF_START);
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties(), currentTime::get);
        OpenAIClient configuredClient = mock(OpenAIClient.class);

        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeoutFailure());
            routingService.recordProviderSuccess(RateLimitService.ApiProvider.OPENAI);

            assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(configuredClient));

            currentTime.set(CONFIGURED_PROVIDER_BACKOFF_START.plusSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS));

            assertDoesNotThrow(() -> routingService.admitConfiguredProviderRequest(configuredClient));
            assertWarningMessages(
                    expectedLogEvents,
                    List.of(
                            "Configured provider temporarily disabled for 600s due to failure",
                            "Configured provider unavailable (backoff active, providerId="
                                    + RateLimitService.ApiProvider.OPENAI.ordinal() + ")"));
        }

        verify(rateLimitService).recordSuccess(RateLimitService.ApiProvider.OPENAI);
    }

    @Test
    void concurrentFailuresNeverMoveConfiguredProviderCooldownDeadlineBackward()
            throws ExecutionException, InterruptedException, TimeoutException {
        AtomicInteger instantReadIndex = new AtomicInteger();
        AtomicReference<Instant> admissionTime = new AtomicReference<>(
                CONFIGURED_PROVIDER_BACKOFF_START.plusSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS));
        InstantSource sequencedInstantSource = () -> switch (instantReadIndex.getAndIncrement()) {
            case 0 -> CONFIGURED_PROVIDER_BACKOFF_START.plusSeconds(1);
            case 1 -> CONFIGURED_PROVIDER_BACKOFF_START;
            default -> admissionTime.get();
        };
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryReserveRequest(RateLimitService.ApiProvider.OPENAI))
                .thenReturn(true);
        OpenAiProviderRoutingService routingService =
                new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties(), sequencedInstantSource);
        OpenAIClient configuredClient = mock(OpenAIClient.class);
        ExecutorService failureRecorder = Executors.newFixedThreadPool(CONCURRENT_FAILURE_RECORDERS);
        CyclicBarrier failureStartBarrier = new CyclicBarrier(CONCURRENT_FAILURE_RECORDERS);
        try (ExpectedLogEvents expectedLogEvents = ExpectedLogEvents.capture(PROVIDER_ROUTING_LOGGER)) {
            Future<?> firstFailureRecording = failureRecorder.submit(() -> {
                failureStartBarrier.await();
                routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeoutFailure());
                return null;
            });
            Future<?> secondFailureRecording = failureRecorder.submit(() -> {
                failureStartBarrier.await();
                routingService.recordProviderFailure(RateLimitService.ApiProvider.OPENAI, gatewayTimeoutFailure());
                return null;
            });

            firstFailureRecording.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondFailureRecording.get(CONCURRENT_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThrows(
                    ConfiguredProviderTemporarilyUnavailableException.class,
                    () -> routingService.admitConfiguredProviderRequest(configuredClient));

            admissionTime.set(CONFIGURED_PROVIDER_BACKOFF_START.plusSeconds(CONFIGURED_PROVIDER_BACKOFF_SECONDS + 1));

            assertDoesNotThrow(() -> routingService.admitConfiguredProviderRequest(configuredClient));
            assertWarningMessages(
                    expectedLogEvents,
                    List.of(
                            "Configured provider temporarily disabled for 600s due to failure",
                            "Configured provider temporarily disabled for 601s due to failure",
                            "Configured provider unavailable (backoff active, providerId="
                                    + RateLimitService.ApiProvider.OPENAI.ordinal() + ")"));
        } finally {
            failureRecorder.shutdownNow();
        }
    }

    @Test
    void rejectsNonPositiveConfiguredProviderBackoffDuringConstruction() {
        IllegalArgumentException zeroBackoffFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class),
                        configuredAppProperties(0),
                        InstantSource.fixed(CONFIGURED_PROVIDER_BACKOFF_START)));
        IllegalArgumentException negativeBackoffFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new OpenAiProviderRoutingService(
                        mock(RateLimitService.class),
                        configuredAppProperties(-1),
                        InstantSource.fixed(CONFIGURED_PROVIDER_BACKOFF_START)));

        assertEquals(expectedConfiguredProviderBackoffFailureMessage(0), zeroBackoffFailure.getMessage());
        assertEquals(expectedConfiguredProviderBackoffFailureMessage(-1), negativeBackoffFailure.getMessage());
    }

    private OpenAIIoException wrappedOkHttpCallTimeout() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(OK_HTTP_CALL_TIMEOUT_MESSAGE));
    }

    private OpenAIIoException wrappedCallerInterruption() {
        return new OpenAIIoException(
                OPENAI_REQUEST_FAILED_MESSAGE, new InterruptedIOException(CALLER_INTERRUPTION_MESSAGE));
    }

    private OpenAiProviderRoutingService createRoutingService(RateLimitService rateLimitService) {
        return new OpenAiProviderRoutingService(rateLimitService, configuredAppProperties());
    }

    private static InternalServerException gatewayTimeoutFailure() {
        return InternalServerException.builder()
                .statusCode(504)
                .headers(Headers.builder().build())
                .build();
    }

    private static AppProperties configuredAppProperties() {
        return configuredAppProperties(CONFIGURED_PROVIDER_BACKOFF_SECONDS);
    }

    private static AppProperties configuredAppProperties(long configuredProviderBackoffSeconds) {
        AppProperties appProperties = new AppProperties();
        appProperties.getLlm().setConfiguredProviderBackoffSeconds(configuredProviderBackoffSeconds);
        return appProperties;
    }

    private static void assertWarningMessages(ExpectedLogEvents expectedLogEvents, List<String> expectedMessages) {
        assertEquals(expectedMessages.size(), expectedLogEvents.events().size());
        for (int eventIndex = 0; eventIndex < expectedMessages.size(); eventIndex++) {
            var warningEvent = expectedLogEvents.events().get(eventIndex);
            assertEquals(Level.WARN, warningEvent.getLevel());
            assertEquals(expectedMessages.get(eventIndex), warningEvent.getFormattedMessage());
            assertNull(warningEvent.getThrowableProxy());
        }
    }

    private static String expectedConfiguredProviderBackoffFailureMessage(long configuredProviderBackoffSeconds) {
        return ConfiguredProviderBackoff.CONFIGURED_PROVIDER_BACKOFF_CONFIGURATION_KEY
                + " must be in range ["
                + ConfiguredProviderBackoff.MIN_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                + ", "
                + ConfiguredProviderBackoff.MAX_CONFIGURED_PROVIDER_BACKOFF_SECONDS
                + "], got: "
                + configuredProviderBackoffSeconds;
    }
}
