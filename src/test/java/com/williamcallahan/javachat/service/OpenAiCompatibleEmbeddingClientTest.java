package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.EmbeddingService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies OpenAI embedding responses preserve request ordering.
 */
class OpenAiCompatibleEmbeddingClientTest {

    private static final int EXPECTED_EMBEDDING_DIMENSION = 2;
    private static final Duration EXPECTED_EMBEDDING_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXPECTED_LIVE_EMBEDDING_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration EXPECTED_BATCH_EMBEDDING_TIMEOUT = Duration.ofMinutes(10);
    private static final int TEST_LIVE_MAX_CONCURRENT_REQUESTS = 4;
    private static final int TEST_BATCH_MAX_CONCURRENT_REQUESTS = 1;
    private static final double TEST_UNTHROTTLED_REQUESTS_PER_SECOND = 1_000.0;
    private static final double TEST_PACED_BATCH_REQUESTS_PER_SECOND = 4.0;
    private static final Duration MINIMUM_EXPECTED_PACING_DELAY = Duration.ofMillis(150);
    private static final Duration TEST_SATURATED_LIVE_REQUEST_BUDGET = Duration.ofMillis(250);
    private static final Duration MAXIMUM_EXPECTED_SATURATION_DELAY = Duration.ofSeconds(1);

    @Test
    void callUsesSdkAndPreservesIndexOrdering() {
        OpenAIClient client = mock(OpenAIClient.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

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

        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

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
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

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

        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

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
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(client.embeddings()).thenReturn(embeddingService);

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

        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(malformedResponse);

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
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(client.embeddings()).thenReturn(embeddingService);
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
        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

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
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(client.embeddings()).thenReturn(embeddingService);

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
        when(embeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

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
        EmbeddingService liveEmbeddingService = mock(EmbeddingService.class);
        EmbeddingService batchEmbeddingService = mock(EmbeddingService.class);

        when(liveClient.embeddings()).thenReturn(liveEmbeddingService);
        when(batchClient.embeddings()).thenReturn(batchEmbeddingService);

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
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenReturn(response);

        try (OpenAiCompatibleEmbeddingClient clientAdapter =
                new OpenAiCompatibleEmbeddingClient(liveClient, batchClient, gatewaySettings())) {
            ArgumentCaptor<RequestOptions> liveRequestOptionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE);
            verify(liveEmbeddingService).create(any(), liveRequestOptionsCaptor.capture());
            verifyNoInteractions(batchEmbeddingService);
            assertEquals(
                    EXPECTED_EMBEDDING_CONNECT_TIMEOUT,
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
                    EXPECTED_EMBEDDING_CONNECT_TIMEOUT,
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
        EmbeddingService liveEmbeddingService = mock(EmbeddingService.class);
        EmbeddingService batchEmbeddingService = mock(EmbeddingService.class);
        CountDownLatch foregroundStarted = new CountDownLatch(1);
        CountDownLatch releaseForeground = new CountDownLatch(1);

        when(liveClient.embeddings()).thenReturn(liveEmbeddingService);
        when(batchClient.embeddings()).thenReturn(batchEmbeddingService);
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            foregroundStarted.countDown();
            assertTrue(releaseForeground.await(5, TimeUnit.SECONDS));
            return successfulResponse();
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
                releaseForeground.countDown();
            }
            assertEquals(1, foregroundEmbedding.get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void foregroundDoesNotWaitForProbeAdmittedBeforeItArrives()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingService liveEmbeddingService = mock(EmbeddingService.class);
        EmbeddingService batchEmbeddingService = mock(EmbeddingService.class);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);

        when(liveClient.embeddings()).thenReturn(liveEmbeddingService);
        when(batchClient.embeddings()).thenReturn(batchEmbeddingService);
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenReturn(successfulResponse());
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            probeStarted.countDown();
            assertTrue(releaseProbe.await(5, TimeUnit.SECONDS));
            return successfulResponse();
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
                releaseProbe.countDown();
            }
            admittedProbe.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void limitsBatchRequestConcurrencyWithoutConsumingLiveRequestCapacity()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingService liveEmbeddingService = mock(EmbeddingService.class);
        EmbeddingService batchEmbeddingService = mock(EmbeddingService.class);
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        CountDownLatch secondBatchStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);
        AtomicInteger batchRequestCount = new AtomicInteger();

        when(liveClient.embeddings()).thenReturn(liveEmbeddingService);
        when(batchClient.embeddings()).thenReturn(batchEmbeddingService);
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenReturn(successfulResponse());
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            int requestNumber = batchRequestCount.incrementAndGet();
            if (requestNumber == 1) {
                firstBatchStarted.countDown();
                assertTrue(releaseFirstBatch.await(5, TimeUnit.SECONDS));
            } else {
                secondBatchStarted.countDown();
            }
            return successfulResponse();
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

            releaseFirstBatch.countDown();
            assertEquals(1, firstBatch.get(5, TimeUnit.SECONDS).size());
            assertEquals(1, secondBatch.get(5, TimeUnit.SECONDS).size());
        } finally {
            releaseFirstBatch.countDown();
        }
    }

    @Test
    void pacesGatewayRequestsAndReleasesConcurrencyAfterFailure() {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingService batchEmbeddingService = mock(EmbeddingService.class);
        AtomicInteger batchRequestCount = new AtomicInteger();
        when(batchClient.embeddings()).thenReturn(batchEmbeddingService);
        when(batchEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            if (batchRequestCount.incrementAndGet() == 1) {
                throw new IllegalStateException("injected transport failure");
            }
            return successfulResponse();
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
            long firstRequestStartNanos = System.nanoTime();
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> embeddingClient.embed(List.of("failed batch"), LlmGatewayTier.BATCH));
            assertEquals(
                    1,
                    embeddingClient
                            .embed(List.of("successful batch"), LlmGatewayTier.BATCH)
                            .size());
            long elapsedNanos = System.nanoTime() - firstRequestStartNanos;

            assertTrue(Duration.ofNanos(elapsedNanos).compareTo(MINIMUM_EXPECTED_PACING_DELAY) >= 0);
            assertEquals(2, batchRequestCount.get());
        }
    }

    @Test
    void boundsLiveAdmissionWaitingByTheWholeRequestDeadline()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIClient liveClient = mock(OpenAIClient.class);
        OpenAIClient batchClient = mock(OpenAIClient.class);
        EmbeddingService liveEmbeddingService = mock(EmbeddingService.class);
        CountDownLatch firstLiveRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLiveRequest = new CountDownLatch(1);
        when(liveClient.embeddings()).thenReturn(liveEmbeddingService);
        when(liveEmbeddingService.create(any(), any(RequestOptions.class))).thenAnswer(invocation -> {
            firstLiveRequestStarted.countDown();
            assertTrue(releaseFirstLiveRequest.await(5, TimeUnit.SECONDS));
            return successfulResponse();
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

            releaseFirstLiveRequest.countDown();
            assertEquals(1, admittedRequest.get(5, TimeUnit.SECONDS).size());
        } finally {
            releaseFirstLiveRequest.countDown();
        }
    }

    @Test
    void usesSingleSdkAttemptForEveryRateLimitedEmbeddingRequest() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestCount.incrementAndGet();
            respondWithRateLimit(exchange);
        });
        server.start();

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", gatewaySettings())) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH));
            assertEquals(1, requestCount.get());

            requestCount.set(0);
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE));
            assertEquals(1, requestCount.get());
        } finally {
            server.stop(0);
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

    private static void respondWithRateLimit(HttpExchange exchange) throws IOException {
        byte[] responseBody = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(429, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }
}
