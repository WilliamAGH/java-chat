package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.boot.web.client.RestTemplateBuilder;

class EmbeddingProviderFailureTest {

    @Test
    void localEmbeddingSurfacesHttpErrors() throws Exception {
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer httpServer = startServer(serverExecutor, new LocalFailureRoutes());
        String baseUrl = baseUrl(httpServer);

        try {
            LocalEmbeddingModel localModel =
                    new LocalEmbeddingModel(baseUrl, "local-test-model", 12, new RestTemplateBuilder());
            EmbeddingServiceUnavailableException thrown = assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> localModel.call(new EmbeddingRequest(List.of("hello"), null)));

            assertTrue(thrown.getMessage().contains("HTTP 500"));
        } finally {
            httpServer.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void remoteEmbeddingHttpErrorsSurfaceStatusCodes() throws Exception {
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer httpServer = startServer(serverExecutor, new RemoteFailureRoutes());
        String baseUrl = baseUrl(httpServer);

        try (OpenAiCompatibleEmbeddingModel remoteModel =
                OpenAiCompatibleEmbeddingModel.create(baseUrl, "test-key", "text-embedding-3-small", 8)) {
            EmbeddingServiceUnavailableException thrown = assertThrows(
                    EmbeddingServiceUnavailableException.class,
                    () -> remoteModel.call(new EmbeddingRequest(List.of("hello"), null)));

            assertTrue(thrown.getMessage().contains("HTTP 401"));
            assertNotNull(thrown.getCause());
            assertTrue(thrown.getCause().getMessage().contains("401"));
        } finally {
            httpServer.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static HttpServer startServer(ExecutorService executorService, RouteRegistrar routeRegistrar)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executorService);
        routeRegistrar.register(server);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getHostString() + ":" + address.getPort();
    }

    private interface RouteRegistrar {
        void register(HttpServer server);
    }

    private static final class LocalFailureRoutes implements RouteRegistrar {
        @Override
        public void register(HttpServer server) {
            server.createContext("/v1/models", exchange -> respond(exchange, 200, "{}"));
            server.createContext("/v1/embeddings", exchange -> respond(exchange, 500, "{\"error\":\"down\"}"));
        }
    }

    private static final class RemoteFailureRoutes implements RouteRegistrar {
        @Override
        public void register(HttpServer server) {
            server.createContext(
                    "/v1/embeddings", exchange -> respond(exchange, 401, "{\"error\":{\"message\":\"unauthorized\"}}"));
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] responseBytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }
}
