package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Verifies the exact OpenAI-compatible gateway HTTP contract used for embeddings. */
class OpenAiCompatibleEmbeddingClientWireTest {
    private static final String EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";
    private static final String TEST_API_KEY = "wire-test-api-key";
    private static final int EMBEDDING_DIMENSIONS = 2_560;
    private static final int BATCH_INPUT_COUNT = 32;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsVersionedGatewayRequestsWithIntentSpecificTiersAndNativeDimensions() throws IOException {
        List<CapturedEmbeddingRequest> capturedRequests = new CopyOnWriteArrayList<>();
        HttpServer embeddingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embeddingServer.createContext("/v1/embeddings", exchange -> handleEmbeddingRequest(exchange, capturedRequests));
        embeddingServer.start();

        String gatewayBaseUrl =
                "http://127.0.0.1:" + embeddingServer.getAddress().getPort() + "/v1";
        try (OpenAiCompatibleEmbeddingClient embeddingClient = OpenAiCompatibleEmbeddingClient.create(
                gatewayBaseUrl, TEST_API_KEY, EMBEDDING_MODEL, EMBEDDING_DIMENSIONS)) {
            List<float[]> liveEmbeddings = embeddingClient.embed(List.of("user retrieval"), LlmGatewayTier.LIVE);
            List<String> batchInputs = new ArrayList<>();
            for (int inputIndex = 0; inputIndex < BATCH_INPUT_COUNT; inputIndex++) {
                batchInputs.add("ingestion input " + inputIndex);
            }
            List<float[]> batchEmbeddings = embeddingClient.embed(batchInputs, LlmGatewayTier.BATCH);

            assertEquals(1, liveEmbeddings.size());
            assertEquals(EMBEDDING_DIMENSIONS, liveEmbeddings.getFirst().length);
            assertEquals(BATCH_INPUT_COUNT, batchEmbeddings.size());
            batchEmbeddings.forEach(embeddingVector -> assertEquals(EMBEDDING_DIMENSIONS, embeddingVector.length));
        } finally {
            embeddingServer.stop(0);
        }

        assertEquals(2, capturedRequests.size());
        assertGatewayRequest(capturedRequests.get(0), "production-z", 1);
        assertGatewayRequest(capturedRequests.get(1), "batch", BATCH_INPUT_COUNT);
    }

    private void handleEmbeddingRequest(HttpExchange exchange, List<CapturedEmbeddingRequest> capturedRequests)
            throws IOException {
        String embeddingRequestJson = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode embeddingRequest = objectMapper.readTree(embeddingRequestJson);
        capturedRequests.add(new CapturedEmbeddingRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Tier"),
                embeddingRequest));

        ArrayNode inputTexts = (ArrayNode) embeddingRequest.path("input");
        byte[] embeddingResponseBytes = embeddingResponse(inputTexts.size()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, embeddingResponseBytes.length);
        try (OutputStream exchangeBody = exchange.getResponseBody()) {
            exchangeBody.write(embeddingResponseBytes);
        }
    }

    private String embeddingResponse(int embeddingCount) throws IOException {
        ObjectNode embeddingResponse = objectMapper.createObjectNode();
        embeddingResponse.put("object", "list");
        embeddingResponse.put("model", EMBEDDING_MODEL);
        ArrayNode embeddingArray = embeddingResponse.putArray("data");
        for (int embeddingIndex = 0; embeddingIndex < embeddingCount; embeddingIndex++) {
            ObjectNode embeddingNode = embeddingArray.addObject();
            embeddingNode.put("object", "embedding");
            embeddingNode.put("index", embeddingIndex);
            ArrayNode vectorComponents = embeddingNode.putArray("embedding");
            for (int vectorPosition = 0; vectorPosition < EMBEDDING_DIMENSIONS; vectorPosition++) {
                vectorComponents.add(embeddingIndex + (vectorPosition / 10_000.0));
            }
        }
        ObjectNode usageNode = embeddingResponse.putObject("usage");
        usageNode.put("prompt_tokens", embeddingCount);
        usageNode.put("total_tokens", embeddingCount);
        return objectMapper.writeValueAsString(embeddingResponse);
    }

    private static void assertGatewayRequest(
            CapturedEmbeddingRequest capturedRequest, String expectedTier, int expectedInputCount) {
        assertEquals("POST", capturedRequest.httpMethod());
        assertEquals("/v1/embeddings", capturedRequest.requestPath());
        assertEquals("Bearer " + TEST_API_KEY, capturedRequest.authorizationHeader());
        assertEquals(expectedTier, capturedRequest.tierHeader());
        assertEquals(
                EMBEDDING_MODEL,
                capturedRequest.embeddingRequest().path("model").asText());
        assertEquals(
                expectedInputCount,
                capturedRequest.embeddingRequest().path("input").size());
        assertFalse(capturedRequest.embeddingRequest().has("dimensions"));
    }

    private record CapturedEmbeddingRequest(
            String httpMethod,
            String requestPath,
            String authorizationHeader,
            String tierHeader,
            JsonNode embeddingRequest) {}
}
