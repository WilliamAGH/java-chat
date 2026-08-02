package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.service.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Verifies real servlet error dispatch renders Java Chat-owned browser responses. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.vectorstore.qdrant.port=1")
@AutoConfigureWebTestClient
class BrowserErrorResponseIntegrationTest {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @Test
    void missingAssetReturnsJavaChatBrowserPage() {
        webTestClient
                .get()
                .uri("/assets/missing.js")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectHeader()
                .value(REQUEST_ID_HEADER, requestId -> assertFalse(requestId.isBlank()))
                .expectBody(String.class)
                .value(this::assertJavaChatNotFoundPage);
    }

    @Test
    void directErrorCatalogRoutesAreNotPublished() {
        List<String> directCatalogPaths = List.of(
                "/errors",
                "/errors/",
                "/errors/not-found",
                "/errors/access-denied",
                "/errors/not-found.html",
                "/errors/access-denied.html");

        for (String directCatalogPath : directCatalogPaths) {
            webTestClient
                    .get()
                    .uri(directCatalogPath)
                    .accept(MediaType.TEXT_HTML)
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
    }

    @Test
    void browserFailuresRenderJavaChatOwnedPages() {
        assertJavaChatErrorPage(400, "Java Chat could not use that request");
        assertJavaChatErrorPage(401, "Java Chat needs you to sign in");
        assertJavaChatErrorPage(403, "Java Chat cannot open that page");
        assertJavaChatErrorPage(500, "Java Chat hit an unexpected error");
    }

    @Test
    void unknownApiPathPreservesJsonContractAndRequestId() {
        webTestClient
                .get()
                .uri("/api/missing")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader()
                .value(REQUEST_ID_HEADER, requestId -> assertFalse(requestId.isBlank()))
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("error")
                .jsonPath("$.message")
                .isEqualTo("Not Found")
                .jsonPath("$.details")
                .value(apiErrorDetails -> assertNull(apiErrorDetails));
    }

    @Test
    void unknownExtensionlessPathReturnsRealNotFound() {
        webTestClient
                .get()
                .uri("/definitely-not-a-page")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(this::assertJavaChatNotFoundPage);
    }

    @Test
    void publishedSpaRoutesForwardToIndexShell() {
        List<String> publishedSpaPaths = List.of(
                "/",
                "/chat",
                "/guided",
                "/learn",
                "/privacy",
                "/contact",
                "/learn/records",
                "/learn/records/fields",
                "/guided/records/fields");

        for (String publishedSpaPath : publishedSpaPaths) {
            webTestClient
                    .get()
                    .uri(publishedSpaPath)
                    .accept(MediaType.TEXT_HTML)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    private void assertJavaChatNotFoundPage(String pageHtml) {
        assertTrue(pageHtml.contains("Java Chat"));
        assertTrue(pageHtml.contains("<h1 class=\"error-type\">Java Chat could not find that page</h1>"));
        assertTrue(pageHtml.contains("<a href=\"/\" class=\"back-link\">"));
        assertFalse(pageHtml.contains("aVenture"));
        assertFalse(pageHtml.contains("api.aventure.vc"));
        assertFalse(pageHtml.contains("/v1/entities"));
        assertFalse(pageHtml.contains("Swagger"));
        assertFalse(pageHtml.contains("RFC 9457"));
    }

    private void assertJavaChatErrorPage(int statusCode, String expectedHeading) {
        webTestClient
                .get()
                .uri("/test-errors/" + statusCode)
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isEqualTo(statusCode)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(pageHtml -> {
                    assertTrue(pageHtml.contains("Java Chat"));
                    assertTrue(pageHtml.contains("<h1 class=\"error-type\">" + expectedHeading + "</h1>"));
                    assertFalse(pageHtml.contains("aVenture"));
                    assertFalse(pageHtml.contains("api.aventure.vc"));
                    assertFalse(pageHtml.contains("/errors/"));
                    assertFalse(pageHtml.contains("Swagger"));
                });
    }
}
