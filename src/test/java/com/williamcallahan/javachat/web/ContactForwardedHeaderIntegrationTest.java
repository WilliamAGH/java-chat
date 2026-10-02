package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Verifies contact rate limiting uses Tomcat's trusted forwarded-header boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ContactForwardedHeaderIntegrationTest.RequestOriginConfiguration.class)
class ContactForwardedHeaderIntegrationTest {
    private static final String CSRF_REFRESH_ENDPOINT = "/api/security/csrf";
    private static final String CONTACT_ENDPOINT = "/api/contact";
    private static final String REQUEST_ORIGIN_ENDPOINT = "/test/request-origin";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String VERIFIED_CLIENT_ADDRESS = "198.51.100.20";
    private static final int ACCEPTED_SUBMISSION_LIMIT = 3;
    private static final int FORWARDED_SERVER_PORT = 8443;
    private static final long LEGITIMATE_RENDER_AGE_MILLIS = 10_000L;

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    JavaMailSender javaMailSender;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @BeforeEach
    void stubMimeMessageCreation() {
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    @Test
    void farLeftSpoofRotationCannotSelectNewRateLimitBuckets() {
        ResponseCookie csrfCookie = requestCsrfCookie();
        for (int submissionIndex = 0; submissionIndex < ACCEPTED_SUBMISSION_LIMIT; submissionIndex++) {
            submitContact(
                            csrfCookie,
                            "203.0.113." + submissionIndex + ", " + VERIFIED_CLIENT_ADDRESS,
                            "Message " + submissionIndex)
                    .expectStatus()
                    .isAccepted();
        }

        submitContact(csrfCookie, "203.0.113.99, " + VERIFIED_CLIENT_ADDRESS, "Rate limited")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        submitContact(csrfCookie, "203.0.113.99, 198.51.100.21", "Independent client")
                .expectStatus()
                .isAccepted();
    }

    @Test
    void nativeForwardingPreservesVerifiedOriginFields() {
        webTestClient
                .get()
                .uri(REQUEST_ORIGIN_ENDPOINT)
                .header("X-Forwarded-For", VERIFIED_CLIENT_ADDRESS)
                .header("X-Forwarded-Proto", "https, https")
                .header("X-Forwarded-Port", Integer.toString(FORWARDED_SERVER_PORT))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.remoteAddress")
                .isEqualTo(VERIFIED_CLIENT_ADDRESS)
                .jsonPath("$.scheme")
                .isEqualTo("https")
                .jsonPath("$.secure")
                .isEqualTo(true)
                .jsonPath("$.serverPort")
                .isEqualTo(FORWARDED_SERVER_PORT);
    }

    private WebTestClient.ResponseSpec submitContact(ResponseCookie csrfCookie, String forwardedFor, String message) {
        return webTestClient
                .post()
                .uri(CONTACT_ENDPOINT)
                .cookie(CSRF_COOKIE_NAME, csrfCookie.getValue())
                .header(CSRF_HEADER_NAME, csrfCookie.getValue())
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(submissionJson(message))
                .exchange();
    }

    private ResponseCookie requestCsrfCookie() {
        EntityExchangeResult<byte[]> csrfExchange = webTestClient
                .get()
                .uri(CSRF_REFRESH_ENDPOINT)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();
        ResponseCookie csrfCookie = csrfExchange.getResponseCookies().getFirst(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);
        return csrfCookie;
    }

    private static String submissionJson(String message) {
        return "{\"name\":\"Proxy Test\",\"email\":\"proxy@example.test\",\"message\":\"%s\",\"website\":\"\",\"renderedAt\":%d}"
                .formatted(message, System.currentTimeMillis() - LEGITIMATE_RENDER_AGE_MILLIS);
    }

    /** Registers the request-origin probe only inside this embedded-server test. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RequestOriginConfiguration {
        @Bean
        RequestOriginController requestOriginController() {
            return new RequestOriginController();
        }
    }

    /** Exposes servlet request fields after the real Tomcat valve runs. */
    @RestController
    static class RequestOriginController {
        @GetMapping(REQUEST_ORIGIN_ENDPOINT)
        RequestOrigin requestOrigin(HttpServletRequest servletRequest) {
            return new RequestOrigin(
                    servletRequest.getRemoteAddr(),
                    servletRequest.getScheme(),
                    servletRequest.isSecure(),
                    servletRequest.getServerPort());
        }
    }

    /** Captures the origin fields presented to application controllers. */
    record RequestOrigin(String remoteAddress, String scheme, boolean secure, int serverPort) {}
}
