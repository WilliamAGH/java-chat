package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.TestConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for chat SSE responses to ensure clean, client-ready text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestConfiguration.RequiresExternalServices
class ChatSseIntegrationTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    @DisplayName("Chat stream returns SSE events and aggregates to non-empty plain text without keepalive artifacts")
    void chatStreamProducesCleanText() {
        boolean hasKey = System.getenv("OPENAI_API_KEY") != null || System.getenv("GITHUB_TOKEN") != null;
        Assumptions.assumeTrue(hasKey, "Skipping live integration test without API credentials");

        Flux<String> body = webTestClient.post()
                .uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"latest\":\"Say hello in one short sentence.\"}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .timeout(Duration.ofSeconds(30));

        // Collect SSE events until terminal [DONE] or timeout
        List<String> sseChunks = Objects.requireNonNull(body
                .takeUntil(chunk -> chunk.contains("[DONE]"))
                .take(Duration.ofSeconds(10))
                .collectList()
                .block(Duration.ofSeconds(15)), "Expected SSE chunks");
        String aggregated = sseChunks.stream().reduce("", (a, b) -> a + b);

        assertNotNull(aggregated);
        assertTrue(aggregated.contains("data:"), "SSE should contain data: prefixes");

        // Convert to plain text (client behavior) and assert no artifacts
        String plain = aggregated
                .replaceAll("(^|\\n)\\s*data:\\s*", "$1")
                .replaceAll("(^|\\n):\\s*keepalive.*", "")
                .replace("\n\n", "\n");

        assertFalse(plain.contains(": keepalive"), "No keepalive visible in plain text");
        assertFalse(plain.contains("{{hint:"), "No raw enrichment markers");
        assertTrue(plain.trim().length() > 0, "Should have non-empty content");
    }
}

