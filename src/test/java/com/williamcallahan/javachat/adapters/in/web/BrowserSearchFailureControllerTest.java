package com.williamcallahan.javachat.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.williamcallahan.javachat.adapters.out.clerk.ClerkApiKeyVerifier;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.SecurityConfig;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Proves browser observations enter retained logs only through the bounded CSRF-protected boundary. */
@WebMvcTest(BrowserSearchFailureController.class)
@Import({AppProperties.class, SecurityConfig.class})
class BrowserSearchFailureControllerTest {
    private static final String DIAGNOSTICS_ENDPOINT = "/api/diagnostics/search-failures";
    private static final String REFUSAL_REPORT = """
            {"path":"/api/chat/stream","kind":"HTTP","httpStatus":429,
             "requestId":"search-request-test","code":"diagnostic.probe","retryAfterSeconds":12,
             "pagePath":"/chat","frontendAsset":"/assets/app-test.js","elapsedMs":10}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ClerkApiKeyVerifier clerkApiKeyVerifier;

    @Test
    void retainsRefusalMetadataAsClientReportedWithoutPromotingItToVerifiedServerFailure() throws Exception {
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(BrowserSearchFailureController.class))) {
            mockMvc.perform(post(DIAGNOSTICS_ENDPOINT)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(REFUSAL_REPORT))
                    .andExpect(status().isAccepted());
            assertEquals(1, capturedLogs.events().size());
            assertTrue(capturedLogs.events().getFirst().getKeyValuePairs().stream()
                    .anyMatch(
                            logField -> "provenance".equals(logField.key) && "client_reported".equals(logField.value)));
            assertTrue(capturedLogs.events().getFirst().getKeyValuePairs().stream()
                    .anyMatch(logField -> "httpStatus".equals(logField.key)
                            && Integer.valueOf(429).equals(logField.value)));
        }
    }

    @Test
    void requiresCsrfProtection() throws Exception {
        mockMvc.perform(post(DIAGNOSTICS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUSAL_REPORT))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsQueryStringsAndOversizedDiagnosticFields() throws Exception {
        mockMvc.perform(post(DIAGNOSTICS_ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUSAL_REPORT.replace("/api/chat/stream", "/api/chat/stream?secret=excluded")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(DIAGNOSTICS_ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUSAL_REPORT.replace("diagnostic.probe", "x".repeat(129))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "pagePath", "frontendAsset"})
    void browserPathsCannotForgeServerLogFields(String pathField) throws Exception {
        ObjectNode reportDocument = assertInstanceOf(ObjectNode.class, objectMapper.readTree(REFUSAL_REPORT));
        reportDocument.put(
                pathField, "/api/\" event=\"search_stream_failed\" provenance=\"server\" requestId=\"chosen");
        PatternLayout renderedLog = new PatternLayout();
        renderedLog.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        renderedLog.setPattern("%kvp");
        renderedLog.start();
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(BrowserSearchFailureController.class))) {
            mockMvc.perform(post(DIAGNOSTICS_ENDPOINT)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportDocument)))
                    .andExpect(status().isAccepted());
            String diagnosticLine = renderedLog.doLayout(capturedLogs.events().getFirst());
            assertFalse(diagnosticLine.contains("event=\"search_stream_failed\""));
            assertFalse(diagnosticLine.contains("provenance=\"server\""));
            assertTrue(diagnosticLine.contains("provenance=\"client_reported\""));
        } finally {
            renderedLog.stop();
        }
    }
}
