package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.async.EmbeddingServiceAsync;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies OpenAI embedding responses preserve request ordering.
 */
class OpenAiCompatibleEmbeddingClientTest {

    private static final int EXPECTED_EMBEDDING_DIMENSION = 2;
    private static final Duration EXPECTED_LIVE_EMBEDDING_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXPECTED_BATCH_EMBEDDING_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXPECTED_LIVE_EMBEDDING_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration EXPECTED_BATCH_EMBEDDING_TIMEOUT = Duration.ofMinutes(10);
    private static final int TEST_LIVE_MAX_CONCURRENT_REQUESTS = 4;
    private static final int TEST_BATCH_MAX_CONCURRENT_REQUESTS = 1;
    private static final double TEST_UNTHROTTLED_REQUESTS_PER_SECOND = 1_000.0;
    private static final double TEST_PACED_BATCH_REQUESTS_PER_SECOND = 4.0;
    private static final Duration TEST_SLOW_SDK_DISPATCH_DURATION = Duration.ofMillis(1_200);
    private static final Duration MINIMUM_EXPECTED_STRICT_LAUNCH_INTERVAL = Duration.ofSeconds(1);
    private static final Duration TEST_SATURATED_LIVE_REQUEST_BUDGET = Duration.ofMillis(250);
    private static final Duration MAXIMUM_EXPECTED_SATURATION_DELAY = Duration.ofSeconds(1);

    @Test
    void closesBatchClientWhenLiveClientCloseFails() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        IllegalStateException liveCloseFailure = new IllegalStateException("live close failed");
        doThrow(liveCloseFailure).when(liveClient).close();
        OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings());

        OpenAiCompatibleEmbeddingClient.EmbeddingClientCloseException thrownFailure = assertThrows(
                OpenAiCompatibleEmbeddingClient.EmbeddingClientCloseException.class, embeddingClient::close);

        assertSame(liveCloseFailure, thrownFailure.getCause());
        verify(batchClient).close();
    }

    @Test
    void suppressesBatchCloseFailureBehindLiveCloseFailure() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        IllegalStateException liveCloseFailure = new IllegalStateException("live close failed");
        IllegalStateException batchCloseFailure = new IllegalStateException("batch close failed");
        doThrow(liveCloseFailure).when(liveClient).close();
        doThrow(batchCloseFailure).when(batchClient).close();
        OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings());

        OpenAiCompatibleEmbeddingClient.EmbeddingClientCloseException thrownFailure = assertThrows(
                OpenAiCompatibleEmbeddingClient.EmbeddingClientCloseException.class, embeddingClient::close);

        assertSame(liveCloseFailure, thrownFailure.getCause());
        assertEquals(1, liveCloseFailure.getSuppressed().length);
        assertSame(batchCloseFailure, liveCloseFailure.getSuppressed()[0]);
    }

    @Test
    void closesSharedClientOnlyOnce() {
        OpenAIClient sharedClient = mock(OpenAIClient.class);
        OpenAiCompatibleEmbeddingClient embeddingClient =
                OpenAiCompatibleEmbeddingClient.create(sharedClient, gatewaySettings());

        embeddingClient.close();

        verify(sharedClient).close();
    }

    @Test
    void callUsesSdkAndPreservesIndexOrdering() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingServiceAsync embeddingService = mockAsyncEmbeddingService(client);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(
                        com.openai.models.embeddings.Embedding.builder()
                                .index(1L)
                                .embedding(List.of(0.0f, 1.0f))
                                .build(),
                        com.openai.models.embeddings.Embedding.builder()
                                .index(0L)
                                .embedding(List.of(0.25f, -0.5f))
                                .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, gatewaySettings())) {
            List<float[]> vectors = clientAdapter.embed(List.of("a", "b"), LlmGatewayTier.LIVE);

            assertEquals(2, vectors.size());
            assertEquals(0.25f, vectors.get(0)[0]);
            assertEquals(-0.5f, vectors.get(0)[1]);
            assertEquals(0.0f, vectors.get(1)[0]);
            assertEquals(1.0f, vectors.get(1)[1]);

            verify(embeddingService).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void throwsWhenEmbeddingDimensionDoesNotMatchConfiguration() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingServiceAsync embeddingService = mockAsyncEmbeddingService(client);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.1f, 0.2f, 0.3f))
                        .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, gatewaySettings())) {
            EmbeddingServiceUnavailableException thrownException = assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("a"), LlmGatewayTier.LIVE));
            assertTrue(thrownException.getMessage().contains("dimension mismatch"));
            verify(embeddingService, times(1)).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void doesNotRetryResponseValidationFailuresOutsideTheSdk() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingServiceAsync embeddingService = mockAsyncEmbeddingService(client);

        CreateEmbeddingResponse malformedResponse = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(10L)
                        .embedding(List.of(0.5f, 0.6f))
                        .build()))
                .build();

        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(malformedResponse));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, gatewaySettings())) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("single"), LlmGatewayTier.LIVE));
            verify(embeddingService).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void throwsWhenEmbeddingResponseOmitsIndex() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingServiceAsync embeddingService = mockAsyncEmbeddingService(client);
        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(com.openai.core.JsonField.ofNullable(null))
                        .embedding(List.of(0.1f, 0.2f))
                        .build()))
                .build();
        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, gatewaySettings())) {
            EmbeddingServiceUnavailableException thrownException = assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("missing index"), LlmGatewayTier.LIVE));

            assertTrue(thrownException.getMessage().contains("omitted index"));
        }
    }

    @Test
    void embed_omitsDimensionsForNonTextEmbedding3Models() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingServiceAsync embeddingService = mockAsyncEmbeddingService(client);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.4f, 0.6f))
                        .build()))
                .build();
        when(embeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                OpenAiCompatibleEmbeddingClient.create(client, gatewaySettings())) {
            clientAdapter.embed(List.of("dimension check"), LlmGatewayTier.LIVE);

            ArgumentCaptor<EmbeddingCreateParams> requestCaptor = ArgumentCaptor.forClass(EmbeddingCreateParams.class);
            verify(embeddingService).create(requestCaptor.capture(), any(RequestOptions.class));
            assertTrue(requestCaptor.getValue().dimensions().isEmpty());
        }
    }

    @Test
    void routesEmbeddingRequestsToTierSpecificSdkClients() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);

        CreateEmbeddingResponse response = CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.4f, 0.6f))
                        .build()))
                .build();
        when(liveEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        when(batchEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            ArgumentCaptor<RequestOptions> liveRequestOptionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE);
            verify(liveEmbeddingService).create(any(), liveRequestOptionsCaptor.capture());
            verifyNoInteractions(batchEmbeddingService);
            assertRemainingTransportBudget(
                    EXPECTED_LIVE_EMBEDDING_CONNECT_TIMEOUT,
                    liveRequestOptionsCaptor.getValue().getTimeout().connect());
            assertRemainingTransportBudget(
                    EXPECTED_LIVE_EMBEDDING_TIMEOUT,
                    liveRequestOptionsCaptor.getValue().getTimeout().request());
            assertRemainingTransportBudget(
                    EXPECTED_LIVE_EMBEDDING_TIMEOUT,
                    liveRequestOptionsCaptor.getValue().getTimeout().read());

            ArgumentCaptor<RequestOptions> batchRequestOptionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH);
            verify(batchEmbeddingService).create(any(), batchRequestOptionsCaptor.capture());
            assertEquals(
                    EXPECTED_BATCH_EMBEDDING_CONNECT_TIMEOUT,
                    batchRequestOptionsCaptor.getValue().getTimeout().connect());
            assertRemainingTransportBudget(
                    EXPECTED_BATCH_EMBEDDING_TIMEOUT,
                    batchRequestOptionsCaptor.getValue().getTimeout().request());
            assertRemainingTransportBudget(
                    EXPECTED_BATCH_EMBEDDING_TIMEOUT,
                    batchRequestOptionsCaptor.getValue().getTimeout().read());
        }
    }

    @Test
    void defersProbeWhenForegroundEmbeddingIsAlreadyActive()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        CountDownLatch foregroundStarted = new CountDownLatch(1);
        CompletableFuture<CreateEmbeddingResponse> foregroundResponseFuture = new CompletableFuture<>();

        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            foregroundStarted.countDown();
            return foregroundResponseFuture;
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                OpenAiCompatibleEmbeddingClient clientAdapter =
                        new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            Future<List<float[]>> foregroundEmbedding =
                    executor.submit(() -> clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE));
            try {
                assertTrue(foregroundStarted.await(5, TimeUnit.SECONDS));

                assertThrows(
                        OpenAiCompatibleEmbeddingClient.EmbeddingProbeDeferredException.class, clientAdapter::warmUp);
                verifyNoInteractions(batchEmbeddingService);
            } finally {
                foregroundResponseFuture.complete(successfulResponse());
            }
            assertEquals(1, foregroundEmbedding.get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void foregroundDoesNotWaitForProbeAdmittedBeforeItArrives()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CompletableFuture<CreateEmbeddingResponse> probeResponseFuture = new CompletableFuture<>();

        when(liveEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(successfulResponse()));
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            probeStarted.countDown();
            return probeResponseFuture;
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                OpenAiCompatibleEmbeddingClient clientAdapter =
                        new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            Future<?> admittedProbe = executor.submit(clientAdapter::warmUp);
            try {
                assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

                assertEquals(
                        1,
                        clientAdapter
                                .embed(List.of("live query"), LlmGatewayTier.LIVE)
                                .size());
                verify(liveEmbeddingService).create(any(), any(RequestOptions.class));
            } finally {
                probeResponseFuture.complete(successfulResponse());
            }
            admittedProbe.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void limitsBatchRequestConcurrencyWithoutConsumingLiveRequestCapacity()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        CountDownLatch secondBatchStarted = new CountDownLatch(1);
        CompletableFuture<CreateEmbeddingResponse> firstBatchResponseFuture = new CompletableFuture<>();
        AtomicInteger batchRequestCount = new AtomicInteger();

        when(liveEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(successfulResponse()));
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            int requestNumber = batchRequestCount.incrementAndGet();
            if (requestNumber == 1) {
                firstBatchStarted.countDown();
                return firstBatchResponseFuture;
            } else {
                secondBatchStarted.countDown();
                return CompletableFuture.completedFuture(successfulResponse());
            }
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                OpenAiCompatibleEmbeddingClient embeddingClient =
                        new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            Future<List<float[]>> firstBatch =
                    executor.submit(() -> embeddingClient.embed(List.of("first batch"), LlmGatewayTier.BATCH));
            assertTrue(firstBatchStarted.await(5, TimeUnit.SECONDS));
            Future<List<float[]>> secondBatch =
                    executor.submit(() -> embeddingClient.embed(List.of("second batch"), LlmGatewayTier.BATCH));

            assertFalse(secondBatchStarted.await(200, TimeUnit.MILLISECONDS));
            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("live query"), LlmGatewayTier.LIVE)
                            .size());

            firstBatchResponseFuture.complete(successfulResponse());
            assertEquals(1, firstBatch.get(5, TimeUnit.SECONDS).size());
            assertEquals(1, secondBatch.get(5, TimeUnit.SECONDS).size());
        } finally {
            firstBatchResponseFuture.complete(successfulResponse());
        }
    }

    @Test
    void releasesConcurrencyWithoutConsumingPacingWhenDispatchFailsBeforeLaunch() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        AtomicInteger batchRequestCount = new AtomicInteger();
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            if (batchRequestCount.incrementAndGet() == 1) {
                throw new IllegalStateException("injected transport failure");
            }
            return CompletableFuture.completedFuture(successfulResponse());
        });
        OpenAiCompatibleEmbeddingClient.GatewaySettings pacedSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        OpenAiCompatibleEmbeddingClient.RequestLimits.live(
                                TEST_LIVE_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND),
                        OpenAiCompatibleEmbeddingClient.RequestLimits.batch(
                                TEST_BATCH_MAX_CONCURRENT_REQUESTS, TEST_PACED_BATCH_REQUESTS_PER_SECOND));

        try (OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, pacedSettings)) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> embeddingClient.embed(List.of("failed batch"), LlmGatewayTier.BATCH));
            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("successful batch"), LlmGatewayTier.BATCH)
                            .size());
            assertEquals(2, batchRequestCount.get());
        }
    }

    @Test
    void preventsSlowProviderCallsFromAccumulatingBurstCredit() throws InterruptedException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        OpenAIClientAsync batchAsyncClient = mock(OpenAIClientAsync.class);
        EmbeddingServiceAsync batchEmbeddingService = mock(EmbeddingServiceAsync.class);
        AtomicInteger embeddingServiceAccessCount = new AtomicInteger();
        AtomicInteger batchRequestCount = new AtomicInteger();
        AtomicLongArray batchRequestStartNanos = new AtomicLongArray(3);
        when(batchClient.async()).thenReturn(batchAsyncClient);
        when(batchAsyncClient.embeddings()).thenAnswer(invocation -> {
            if (embeddingServiceAccessCount.getAndIncrement() == 0) {
                TimeUnit.NANOSECONDS.sleep(TEST_SLOW_SDK_DISPATCH_DURATION.toNanos());
            }
            return batchEmbeddingService;
        });
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            int requestNumber = batchRequestCount.getAndIncrement();
            batchRequestStartNanos.set(requestNumber, System.nanoTime());
            return CompletableFuture.completedFuture(successfulResponse());
        });
        OpenAiCompatibleEmbeddingClient.GatewaySettings pacedSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        OpenAiCompatibleEmbeddingClient.RequestLimits.live(
                                TEST_LIVE_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND),
                        OpenAiCompatibleEmbeddingClient.RequestLimits.batch(TEST_BATCH_MAX_CONCURRENT_REQUESTS, 1.0));

        try (OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, pacedSettings)) {
            embeddingClient.embed(List.of("slow batch"), LlmGatewayTier.BATCH);
            embeddingClient.embed(List.of("second batch"), LlmGatewayTier.BATCH);
            embeddingClient.embed(List.of("third batch"), LlmGatewayTier.BATCH);
        }

        Duration firstToSecondLaunchInterval =
                Duration.ofNanos(batchRequestStartNanos.get(1) - batchRequestStartNanos.get(0));
        Duration secondToThirdLaunchInterval =
                Duration.ofNanos(batchRequestStartNanos.get(2) - batchRequestStartNanos.get(1));
        assertTrue(firstToSecondLaunchInterval.compareTo(MINIMUM_EXPECTED_STRICT_LAUNCH_INTERVAL) >= 0);
        assertTrue(secondToThirdLaunchInterval.compareTo(MINIMUM_EXPECTED_STRICT_LAUNCH_INTERVAL) >= 0);
        assertEquals(3, batchRequestCount.get());
    }

    @Test
    void pacingDeadlineExpiresWithoutDispatchingAnotherRequest() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        when(batchEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(successfulResponse()));
        OpenAiCompatibleEmbeddingClient.GatewaySettings deadlineSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        OpenAiCompatibleEmbeddingClient.RequestLimits.live(
                                TEST_LIVE_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND),
                        new OpenAiCompatibleEmbeddingClient.RequestLimits(
                                TEST_BATCH_MAX_CONCURRENT_REQUESTS, 1.0, TEST_SATURATED_LIVE_REQUEST_BUDGET));

        try (OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, deadlineSettings)) {
            embeddingClient.embed(List.of("admitted batch"), LlmGatewayTier.BATCH);

            long rejectedRequestStartNanos = System.nanoTime();
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> embeddingClient.embed(List.of("expired batch"), LlmGatewayTier.BATCH));
            Duration rejectionDelay = Duration.ofNanos(System.nanoTime() - rejectedRequestStartNanos);

            assertTrue(rejectionDelay.compareTo(TEST_SATURATED_LIVE_REQUEST_BUDGET) >= 0);
            assertTrue(rejectionDelay.compareTo(MAXIMUM_EXPECTED_SATURATION_DELAY) < 0);
            verify(batchEmbeddingService, times(1)).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void interruptionDuringPacingPreventsDispatchAndReleasesAdmissionPermits() throws InterruptedException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        when(batchEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.completedFuture(successfulResponse()));
        OpenAiCompatibleEmbeddingClient.GatewaySettings interruptibleSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        OpenAiCompatibleEmbeddingClient.RequestLimits.live(
                                TEST_LIVE_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND),
                        new OpenAiCompatibleEmbeddingClient.RequestLimits(
                                TEST_BATCH_MAX_CONCURRENT_REQUESTS, 1.0, Duration.ofSeconds(5)));
        AtomicBoolean interruptedStatusRestored = new AtomicBoolean();
        CountDownLatch pacingRequestFinished = new CountDownLatch(1);

        try (OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, interruptibleSettings)) {
            embeddingClient.embed(List.of("admitted batch"), LlmGatewayTier.BATCH);
            Thread pacingRequestThread = Thread.ofVirtual().start(() -> {
                try {
                    embeddingClient.embed(List.of("interrupted batch"), LlmGatewayTier.BATCH);
                } catch (EmbeddingServiceUnavailableException expectedException) {
                    interruptedStatusRestored.set(Thread.currentThread().isInterrupted());
                } finally {
                    pacingRequestFinished.countDown();
                }
            });

            TimeUnit.MILLISECONDS.sleep(100);
            pacingRequestThread.interrupt();
            assertTrue(pacingRequestFinished.await(5, TimeUnit.SECONDS));
            assertTrue(interruptedStatusRestored.get());
            verify(batchEmbeddingService, times(1)).create(any(), any(RequestOptions.class));

            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("recovered batch"), LlmGatewayTier.BATCH)
                            .size());
            verify(batchEmbeddingService, times(2)).create(any(), any(RequestOptions.class));
        }
    }

    @Test
    void boundsLiveAdmissionWaitingByTheWholeRequestDeadline()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        CountDownLatch firstLiveRequestStarted = new CountDownLatch(1);
        CompletableFuture<CreateEmbeddingResponse> firstLiveResponseFuture = new CompletableFuture<>();
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            firstLiveRequestStarted.countDown();
            return firstLiveResponseFuture;
        });
        OpenAiCompatibleEmbeddingClient.GatewaySettings saturatedSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        new OpenAiCompatibleEmbeddingClient.RequestLimits(
                                1, TEST_UNTHROTTLED_REQUESTS_PER_SECOND, TEST_SATURATED_LIVE_REQUEST_BUDGET),
                        OpenAiCompatibleEmbeddingClient.RequestLimits.batch(
                                TEST_BATCH_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                OpenAiCompatibleEmbeddingClient embeddingClient =
                        new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, saturatedSettings)) {
            Future<List<float[]>> admittedRequest =
                    executor.submit(() -> embeddingClient.embed(List.of("admitted query"), LlmGatewayTier.LIVE));
            assertTrue(firstLiveRequestStarted.await(5, TimeUnit.SECONDS));

            long saturatedRequestStartNanos = System.nanoTime();
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> embeddingClient.embed(List.of("saturated query"), LlmGatewayTier.LIVE));
            Duration saturationDelay = Duration.ofNanos(System.nanoTime() - saturatedRequestStartNanos);

            assertTrue(saturationDelay.compareTo(TEST_SATURATED_LIVE_REQUEST_BUDGET) >= 0);
            assertTrue(saturationDelay.compareTo(MAXIMUM_EXPECTED_SATURATION_DELAY) < 0);
            verify(liveEmbeddingService, times(1)).create(any(), any(RequestOptions.class));

            ExecutionException admittedRequestFailure =
                    assertThrows(ExecutionException.class, () -> admittedRequest.get(5, TimeUnit.SECONDS));
            assertTrue(admittedRequestFailure.getCause() instanceof EmbeddingServiceUnavailableException);
            firstLiveResponseFuture.complete(successfulResponse());
            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("recovered query"), LlmGatewayTier.LIVE)
                            .size());
            verify(liveEmbeddingService, times(2)).create(any(), any(RequestOptions.class));
        } finally {
            firstLiveResponseFuture.complete(successfulResponse());
        }
    }

    @Test
    void batchRetryAfterBlocksLiveRequestsWithoutDispatchingThem() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        when(batchEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(rateLimitFailure()));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH));
            EmbeddingServiceUnavailableException liveCooldownFailure = assertThrows(
                    EmbeddingServiceTemporarilyUnavailableException.class,
                    () -> clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE));

            assertTrue(liveCooldownFailure.getMessage().contains("rate limited"));
            verify(batchEmbeddingService).create(any(), any(RequestOptions.class));
            verifyNoInteractions(liveEmbeddingService);
        }
    }

    @Test
    void liveRetryAfterBlocksBatchRequestsWithoutDispatchingThem() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        EmbeddingServiceAsync batchEmbeddingService = mockAsyncEmbeddingService(batchClient);
        when(liveEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(rateLimitFailure()));

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE));
            EmbeddingServiceUnavailableException batchCooldownFailure = assertThrows(
                    EmbeddingServiceTemporarilyUnavailableException.class,
                    () -> clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH));

            assertTrue(batchCooldownFailure.getMessage().contains("rate limited"));
            verify(liveEmbeddingService).create(any(), any(RequestOptions.class));
            verifyNoInteractions(batchEmbeddingService);
        }
    }

    @Test
    void lateRateLimitAfterCallerInterruptionPublishesCooldownAndReleasesPermit() throws InterruptedException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingServiceAsync liveEmbeddingService = mockAsyncEmbeddingService(liveClient);
        CompletableFuture<CreateEmbeddingResponse> interruptedRequestFuture = new CompletableFuture<>();
        CountDownLatch interruptedRequestDispatched = new CountDownLatch(1);
        CountDownLatch interruptedRequestFinished = new CountDownLatch(1);
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        when(liveEmbeddingService.create(any(), any(RequestOptions.class)))
                .thenAnswer(invocation -> {
                    interruptedRequestDispatched.countDown();
                    return interruptedRequestFuture;
                })
                .thenReturn(CompletableFuture.completedFuture(successfulResponse()));
        OpenAiCompatibleEmbeddingClient.GatewaySettings singlePermitSettings =
                new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                        "qwen/qwen3-embedding-4b",
                        EXPECTED_EMBEDDING_DIMENSION,
                        new OpenAiCompatibleEmbeddingClient.RequestLimits(
                                1, TEST_UNTHROTTLED_REQUESTS_PER_SECOND, TEST_SATURATED_LIVE_REQUEST_BUDGET),
                        OpenAiCompatibleEmbeddingClient.RequestLimits.batch(
                                TEST_BATCH_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND));

        try (OpenAiCompatibleEmbeddingClient embeddingClient =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, singlePermitSettings)) {
            Thread interruptedCaller = Thread.ofVirtual().start(() -> {
                try {
                    embeddingClient.embed(List.of("interrupted query"), LlmGatewayTier.LIVE);
                } catch (EmbeddingServiceTemporarilyUnavailableException expectedInterruption) {
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                } finally {
                    interruptedRequestFinished.countDown();
                }
            });
            assertTrue(interruptedRequestDispatched.await(5, TimeUnit.SECONDS));
            interruptedCaller.interrupt();
            assertTrue(interruptedRequestFinished.await(5, TimeUnit.SECONDS));
            assertTrue(callerInterruptRestored.get());

            RateLimitException lateRateLimitFailure = RateLimitException.builder()
                    .headers(Headers.builder().put("Retry-After", "1").build())
                    .build();
            assertTrue(interruptedRequestFuture.completeExceptionally(lateRateLimitFailure));

            long cooldownRejectionStartNanos = System.nanoTime();
            assertThrows(
                    EmbeddingServiceTemporarilyUnavailableException.class,
                    () -> embeddingClient.embed(List.of("cooldown query"), LlmGatewayTier.LIVE));
            Duration cooldownRejectionDelay = Duration.ofNanos(System.nanoTime() - cooldownRejectionStartNanos);

            assertTrue(cooldownRejectionDelay.compareTo(Duration.ofMillis(250)) < 0);
            verify(liveEmbeddingService, times(1)).create(any(), any(RequestOptions.class));

            TimeUnit.MILLISECONDS.sleep(1_100);
            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("recovered query"), LlmGatewayTier.LIVE)
                            .size());
            verify(liveEmbeddingService, times(2)).create(any(), any(RequestOptions.class));
        }
    }

    private static CreateEmbeddingResponse successfulResponse() {
        return CreateEmbeddingResponse.builder()
                .model("qwen/qwen3-embedding-4b")
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(1L)
                        .totalTokens(1L)
                        .build())
                .data(List.of(com.openai.models.embeddings.Embedding.builder()
                        .index(0L)
                        .embedding(List.of(0.4f, 0.6f))
                        .build()))
                .build();
    }

    private static RateLimitException rateLimitFailure() {
        return RateLimitException.builder()
                .headers(Headers.builder().put("Retry-After", "3600").build())
                .build();
    }

    private static EmbeddingServiceAsync mockAsyncEmbeddingService(OpenAIClient client) {
        OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        EmbeddingServiceAsync embeddingService = mock(EmbeddingServiceAsync.class);
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.embeddings()).thenReturn(embeddingService);
        return embeddingService;
    }

    private static OpenAiCompatibleEmbeddingClient.GatewaySettings gatewaySettings() {
        return new OpenAiCompatibleEmbeddingClient.GatewaySettings(
                "qwen/qwen3-embedding-4b",
                EXPECTED_EMBEDDING_DIMENSION,
                OpenAiCompatibleEmbeddingClient.RequestLimits.live(
                        TEST_LIVE_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND),
                OpenAiCompatibleEmbeddingClient.RequestLimits.batch(
                        TEST_BATCH_MAX_CONCURRENT_REQUESTS, TEST_UNTHROTTLED_REQUESTS_PER_SECOND));
    }

    private static void assertRemainingTransportBudget(Duration totalRequestBudget, Duration transportBudget) {
        assertTrue(transportBudget.compareTo(totalRequestBudget) <= 0);
        assertTrue(transportBudget.compareTo(totalRequestBudget.minusSeconds(1)) >= 0);
    }
}
