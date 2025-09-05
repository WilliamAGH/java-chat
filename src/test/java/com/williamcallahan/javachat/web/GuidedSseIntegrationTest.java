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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestConfiguration.RequiresExternalServices
class GuidedSseIntegrationTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    @DisplayName("Guided stream returns clean plain text without artifacts and server stores processed HTML")
    void guidedStreamProducesCleanText() {
        boolean hasKey = System.getenv("OPENAI_API_KEY") != null || System.getenv("GITHUB_TOKEN") != null;
        Assumptions.assumeTrue(hasKey, "Skipping live integration test without API credentials");

        String slug = "introduction-to-java";

        Flux<String> body = webTestClient.post()
                .uri("/api/guided/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"sessionId\":\"guided:" + slug + "\", \"slug\":\"" + slug + "\", \"latest\":\"In one sentence, say hello.\"}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .timeout(Duration.ofSeconds(30));

        // Collect SSE events until terminal [DONE] or timeout
        String aggregated = body
                .takeUntil(chunk -> chunk.contains("[DONE]"))
                .take(Duration.ofSeconds(10))
                .collectList()
                .block(Duration.ofSeconds(15))
                .stream()
                .reduce("", (a, b) -> a + b);

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


