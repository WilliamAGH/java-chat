package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/**
 * Verifies local embedding batching and dimension validation behavior.
 */
class LocalEmbeddingClientTest {

    @Test
    void batchesRequestsAndPreservesEmbeddingOrderByIndex() throws IOException {
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger requestCounter = new AtomicInteger();
        List<Integer> observedBatchSizes = new ArrayList<>();

        httpServer.createContext("/v1/embeddings", exchange -> {
            int requestIndex = requestCounter.getAndIncrement();
            String requestJson = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode requestNode = objectMapper.readTree(requestJson);
            int observedBatchSize = requestNode.path("input").size();
            observedBatchSizes.add(observedBatchSize);
            if (requestIndex == 0) {
                respondJson(
                        exchange,
                        200,
                        "{\"data\":[{\"index\":1,\"embedding\":[9.0,9.0,9.0]},{\"index\":0,\"embedding\":[1.0,1.0,1.0]}]}");
                return;
            }
            respondJson(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[7.0,7.0,7.0]}]}");
        });

        httpServer.setExecutor(serverExecutor);
        httpServer.start();
        String baseUrl = "http://" + httpServer.getAddress().getHostString() + ":"
                + httpServer.getAddress().getPort();

        try {
            LocalEmbeddingClient localEmbeddingClient =
                    new LocalEmbeddingClient(baseUrl, "local-model", 3, 2, new RestTemplateBuilder());
            List<float[]> embeddingVectors = localEmbeddingClient.embed(List.of("alpha", "beta", "gamma"));

            assertEquals(2, requestCounter.get());
            assertEquals(List.of(2, 1), observedBatchSizes);
            assertEquals(3, embeddingVectors.size());
            assertEquals(1.0f, embeddingVectors.get(0)[0]);
            assertEquals(9.0f, embeddingVectors.get(1)[0]);
            assertEquals(7.0f, embeddingVectors.get(2)[0]);
        } finally {
            httpServer.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void failsWhenLocalEmbeddingDimensionsDoNotMatchConfiguredDimensions() throws IOException {
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        httpServer.createContext(
                "/v1/embeddings",
                exchange -> respondJson(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}"));

        httpServer.setExecutor(serverExecutor);
        httpServer.start();
        String baseUrl = "http://" + httpServer.getAddress().getHostString() + ":"
                + httpServer.getAddress().getPort();

        try {
            LocalEmbeddingClient localEmbeddingClient =
                    new LocalEmbeddingClient(baseUrl, "local-model", 3, 8, new RestTemplateBuilder());
            EmbeddingServiceUnavailableException thrownException = assertThrows(
                    EmbeddingServiceUnavailableException.class, () -> localEmbeddingClient.embed(List.of("alpha")));
            assertTrue(thrownException.getMessage().contains("dimension mismatch"));
        } finally {
            httpServer.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static void respondJson(HttpExchange exchange, int statusCode, String responseJson) throws IOException {
        byte[] jsonBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(jsonBytes);
        }
    }
}
