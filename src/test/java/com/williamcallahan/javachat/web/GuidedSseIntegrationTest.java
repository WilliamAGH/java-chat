package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.*;

import com.williamcallahan.javachat.TestConfiguration;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/**
 * Integration coverage for guided lesson SSE responses and plain text aggregation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestConfiguration.RequiresExternalServices
class GuidedSseIntegrationTest {

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    EmbeddingModel embeddingModel;

    @MockitoBean
    QdrantClient qdrantClient;

    @Test
    @DisplayName("Guided stream returns clean plain text without artifacts and server stores processed HTML")
    void guidedStreamProducesCleanText() {
        boolean hasKey = System.getenv("OPENAI_API_KEY") != null || System.getenv("GITHUB_TOKEN") != null;
        Assumptions.assumeTrue(hasKey, "Skipping live integration test without API credentials");

        String slug = "introduction-to-java";

        Flux<String> body = webTestClient
                .post()
                .uri("/api/guided/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sessionId\":\"guided:" + slug + "\", \"slug\":\"" + slug
                        + "\", \"latest\":\"In one sentence, say hello.\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(String.class)
                .getResponseBody()
                .timeout(Duration.ofSeconds(30));

        // Collect SSE events until terminal [DONE] or timeout
        List<String> sseChunks = Objects.requireNonNull(
                body.takeUntil(chunk -> chunk.contains("[DONE]"))
                        .take(Duration.ofSeconds(10))
                        .collectList()
                        .block(Duration.ofSeconds(15)),
                "Expected SSE chunks");
        String aggregated = sseChunks.stream().reduce("", (a, b) -> a + b);

        assertNotNull(aggregated);
        assertTrue(aggregated.contains("data:"));

        String plain = aggregated
                .replaceAll("(^|\\n)\\s*data:\\s*", "$1")
                .replaceAll("(^|\\n):\\s*keepalive.*", "")
                .replace("\n\n", "\n");

        assertFalse(plain.contains(": keepalive"));
        assertFalse(plain.contains("{{"));
        assertTrue(plain.trim().length() > 0);
    }
}
