package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import com.williamcallahan.javachat.adapters.out.clerk.ClerkApiKeyVerifier;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import com.williamcallahan.javachat.application.auth.VerifiedApiKey;
import com.williamcallahan.javachat.application.knowledge.KnowledgeBaseInventoryUseCase;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import io.qdrant.client.QdrantClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Acceptance guard for the API-key id log-leak fix: driving the real controller
 * with a production-shaped Clerk transport failure must keep the rendered CONSOLE
 * log line free of the api key id while preserving the stack trace for diagnosis.
 *
 * <p>Boots the full context with the dev-shaped Clerk properties so the
 * {@code logback-spring.xml} CONSOLE pattern (which uses the redacting throwable
 * converter) is the live, shipped configuration under test. The verifier's
 * {@code revoke} throws an exception built by driving a real {@link RestClient}
 * against a connection-refusing transport, so the cause chain carries a genuine
 * Spring {@link ResourceAccessException} whose message embeds the revoke URI —
 * the exact channel the redaction must cover.
 */
@SpringBootTest(
        properties = {
            "spring.ai.vectorstore.qdrant.port=1",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://romantic-cow-6.clerk.accounts.dev",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://romantic-cow-6.clerk.accounts.dev/.well-known/jwks.json",
            "app.clerk.authorized-parties=http://localhost:5173"
        })
@AutoConfigureMockMvc
class RevokeApiKeyIdLogLeakTest {

    private static final String CLERK_USER_ID = "user_2abcDEFGHijkLMNopq";

    private static final String CLERK_API_KEY_SECRET = "ak_secret_0123456789abcdef0123456789abcdef";

    private static final String CLERK_API_KEY_ID = "ak_0123456789abcdef0123456789abcdef";

    private static final String CONSOLE_APPENDER_NAME = "CONSOLE";

    private static final String CLERK_REVOKE_ENDPOINT = "https://api.clerk.com/v1/api_keys/{apiKeyId}/revoke";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @MockitoBean
    ClerkApiKeyVerifier clerkApiKeyVerifier;

    @MockitoBean
    KnowledgeBaseInventoryUseCase knowledgeBaseInventoryUseCase;

    @Test
    void renderedRevocationLogExcludesApiKeyId() throws Exception {
        when(clerkApiKeyVerifier.verify(CLERK_API_KEY_SECRET))
                .thenReturn(Optional.of(new VerifiedApiKey(CLERK_API_KEY_ID, CLERK_USER_ID)));
        doThrow(transportShapedRevocationFailure(CLERK_API_KEY_ID))
                .when(clerkApiKeyVerifier)
                .revoke(CLERK_API_KEY_ID);

        Logger controllerLogger = (Logger) LoggerFactory.getLogger(AuthenticatedUserController.class);

        try (ExpectedLogEvents revocationLogEvents = ExpectedLogEvents.capture(controllerLogger)) {
            mockMvc.perform(delete("/api/me/api-key").header("Authorization", "Bearer " + CLERK_API_KEY_SECRET))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.message")
                            .value("API key revocation is temporarily unavailable. Please retry."));

            assertEquals(1, revocationLogEvents.events().size(), "the controller must log the revocation exactly once");
            ILoggingEvent revocationLogEvent = revocationLogEvents.events().getFirst();
            assertEquals(Level.ERROR, revocationLogEvent.getLevel());
            assertEquals("Clerk API key revocation was unavailable", revocationLogEvent.getFormattedMessage());

            String renderedLine =
                    consolePatternEncoder(controllerLogger).getLayout().doLayout(revocationLogEvent);

            assertFalse(
                    renderedLine.contains(CLERK_API_KEY_ID),
                    "rendered CONSOLE output must not leak the api key id: " + renderedLine);
            assertTrue(
                    renderedLine.contains("ak_***"),
                    "rendered CONSOLE output must show the redacted marker: " + renderedLine);
            assertTrue(
                    renderedLine.contains("ApiKeyOperationUnavailableException"),
                    "stack trace must remain for diagnosis: " + renderedLine);
            assertTrue(
                    renderedLine.contains("Caused by: org.springframework.web.client.ResourceAccessException"),
                    "cause chain must remain for diagnosis: " + renderedLine);
            assertTrue(
                    renderedLine.contains("Connection refused"),
                    "underlying transport message must remain for diagnosis: " + renderedLine);
        }
    }

    /**
     * Produces the {@link ApiKeyOperationUnavailableException} the production
     * {@code revoke} catch block raises on a Clerk transport failure, by driving a
     * real {@link RestClient} POST against the revoke endpoint with a
     * connection-refusing transport so Spring builds a genuine
     * {@link ResourceAccessException} whose message embeds the resolved URI.
     */
    private static ApiKeyOperationUnavailableException transportShapedRevocationFailure(String apiKeyId) {
        ClientHttpRequestFactory connectionRefusingFactory =
                (requestUri, requestMethod) -> new ConnectionRefusingClientHttpRequest(requestUri, requestMethod);
        try {
            RestClient.builder()
                    .requestFactory(connectionRefusingFactory)
                    .build()
                    .post()
                    .uri(CLERK_REVOKE_ENDPOINT, apiKeyId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"revocation_reason\":\"JavaChat CLI logout\"}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException transportFailure) {
            return new ApiKeyOperationUnavailableException("Clerk API key revocation failed", transportFailure);
        }
        throw new AssertionError("a connection-refusing revoke request must fail with a transport error");
    }

    private PatternLayoutEncoder consolePatternEncoder(Logger controllerLogger) {
        Appender<ILoggingEvent> rootConsoleAppender = controllerLogger
                .getLoggerContext()
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                .getAppender(CONSOLE_APPENDER_NAME);
        if (!(rootConsoleAppender instanceof ConsoleAppender<?> consoleAppender)) {
            throw new AssertionError("Root logger must use the configured " + CONSOLE_APPENDER_NAME + " appender");
        }
        if (!(consoleAppender.getEncoder() instanceof PatternLayoutEncoder patternEncoder)) {
            throw new AssertionError("Console appender must use a pattern layout encoder");
        }
        if (patternEncoder.getLayout() == null) {
            throw new AssertionError("Console pattern layout must be initialized");
        }
        return patternEncoder;
    }

    /** A {@link ClientHttpRequest} whose execution always refuses the connection, mirroring a Clerk outage. */
    private static final class ConnectionRefusingClientHttpRequest implements ClientHttpRequest {

        private final URI requestUri;

        private final HttpMethod requestMethod;

        private final HttpHeaders requestHeaders = new HttpHeaders();

        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        private final Map<String, Object> requestAttributes = new HashMap<>();

        ConnectionRefusingClientHttpRequest(URI requestUri, HttpMethod requestMethod) {
            this.requestUri = requestUri;
            this.requestMethod = requestMethod;
        }

        @Override
        public HttpMethod getMethod() {
            return requestMethod;
        }

        @Override
        public URI getURI() {
            return requestUri;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return requestAttributes;
        }

        @Override
        public HttpHeaders getHeaders() {
            return requestHeaders;
        }

        @Override
        public ByteArrayOutputStream getBody() {
            return requestBody;
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            throw new ConnectException("Connection refused");
        }
    }
}
