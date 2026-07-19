package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                client, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                client, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                client, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                client, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                client, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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

        try (OpenAiCompatibleEmbeddingClient clientAdapter = new OpenAiCompatibleEmbeddingClient(
                liveClient, batchClient, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
            clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE);
            verify(liveEmbeddingService).create(any(), any(RequestOptions.class));
            verifyNoInteractions(batchEmbeddingService);

            clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH);
            verify(batchEmbeddingService).create(any(), any(RequestOptions.class));
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
                OpenAiCompatibleEmbeddingClient clientAdapter = new OpenAiCompatibleEmbeddingClient(
                        liveClient, batchClient, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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
                OpenAiCompatibleEmbeddingClient clientAdapter = new OpenAiCompatibleEmbeddingClient(
                        liveClient, batchClient, "qwen/qwen3-embedding-4b", EXPECTED_EMBEDDING_DIMENSION)) {
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
    void usesSdkRetryBudgetForLiveAndSingleAttemptForBatch() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestCount.incrementAndGet();
            respondWithRateLimit(exchange);
        });
        server.start();

        try (OpenAiCompatibleEmbeddingClient clientAdapter = OpenAiCompatibleEmbeddingClient.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                "qwen/qwen3-embedding-4b",
                EXPECTED_EMBEDDING_DIMENSION)) {
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("batch document"), LlmGatewayTier.BATCH));
            assertEquals(1, requestCount.get());

            requestCount.set(0);
            assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> clientAdapter.embed(List.of("live query"), LlmGatewayTier.LIVE));
            assertEquals(3, requestCount.get());
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

    private static void respondWithRateLimit(HttpExchange exchange) throws IOException {
        byte[] responseBody = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "0");
        exchange.sendResponseHeaders(429, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }
}
