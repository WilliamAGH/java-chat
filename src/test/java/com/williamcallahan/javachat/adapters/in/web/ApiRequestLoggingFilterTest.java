package com.williamcallahan.javachat.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies that an escaping exception is not recorded as a successful response before error dispatch. */
class ApiRequestLoggingFilterTest {
    @Test
    void recordsTheErrorDispatchOutcomeInsteadOfAPrematureSuccess() throws Exception {
        ApiRequestLoggingFilter requestLogging = new ApiRequestLoggingFilter();
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/chat/citations");
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(ApiRequestLoggingFilter.class))) {
            assertThrows(
                    ServletException.class,
                    () -> requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> {
                        throw new ServletException("Retrieval failed");
                    }));
            assertTrue(capturedLogs.events().stream()
                    .noneMatch(logEvent -> "API request completed".equals(logEvent.getFormattedMessage())));
            apiRequest.setDispatcherType(DispatcherType.ERROR);
            apiRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/chat/citations");
            apiRequest.setRequestURI("/error");
            apiResponse.setStatus(500);
            requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> {});
            var completedRequests = capturedLogs.events().stream()
                    .filter(logEvent -> "API request completed".equals(logEvent.getFormattedMessage()))
                    .toList();
            assertEquals(1, completedRequests.size());
            assertTrue(completedRequests.getFirst().getKeyValuePairs().stream()
                    .anyMatch(logField -> "status".equals(logField.key)
                            && Integer.valueOf(500).equals(logField.value)));
        }
    }
}
