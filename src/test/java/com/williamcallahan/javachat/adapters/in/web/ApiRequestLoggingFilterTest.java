package com.williamcallahan.javachat.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies final HTTP completion records across normal, asynchronous, and error dispatches. */
class ApiRequestLoggingFilterTest {
    @Test
    void recordsTheErrorDispatchOutcomeInsteadOfAPrematureSuccess() throws ServletException, IOException {
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

    @ParameterizedTest
    @CsvSource({"REQUEST,false", "REQUEST,true", "ASYNC,false", "ASYNC,true"})
    void waitsForTheRenderedErrorAfterSendErrorReturns(DispatcherType dispatchType, boolean includeMessage)
            throws ServletException, IOException {
        ApiRequestLoggingFilter requestLogging = new ApiRequestLoggingFilter();
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/chat/citations");
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        apiRequest.setAsyncSupported(true);
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(ApiRequestLoggingFilter.class))) {
            if (dispatchType == DispatcherType.ASYNC) {
                requestLogging.doFilter(
                        apiRequest,
                        apiResponse,
                        (servletRequest, servletResponse) ->
                                servletRequest.startAsync(servletRequest, servletResponse));
                assertTrue(completedRequests(capturedLogs).isEmpty());
                apiRequest.setAsyncStarted(false);
                apiRequest.setDispatcherType(DispatcherType.ASYNC);
            }
            requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> {
                HttpServletResponse errorResponse = (HttpServletResponse) servletResponse;
                if (includeMessage) {
                    errorResponse.sendError(503, "Retrieval failed");
                } else {
                    errorResponse.sendError(503);
                }
            });
            assertEquals(503, apiResponse.getStatus());
            assertTrue(completedRequests(capturedLogs).isEmpty());

            // MockMvc does not perform the container's error-page dispatch or reopen its response.
            apiResponse.setCommitted(false);
            apiRequest.setDispatcherType(DispatcherType.ERROR);
            apiRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/chat/citations");
            apiRequest.setRequestURI("/error");
            requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> {
                // The error page, not the status passed to sendError, owns the final response.
                ((HttpServletResponse) servletResponse).setStatus(502);
                ((HttpServletResponse) servletResponse).setHeader("Retry-After", "12");
            });
            assertEquals(502, apiResponse.getStatus());
            assertEquals(1, completedRequests(capturedLogs).size());
            ILoggingEvent completion = completedRequests(capturedLogs).getFirst();
            String requestId = Objects.requireNonNull(apiResponse.getHeader("X-Request-ID"));
            assertEquals(Level.WARN, completion.getLevel());
            assertTrue(completion.getKeyValuePairs().stream()
                    .anyMatch(logField -> "status".equals(logField.key)
                            && Integer.valueOf(502).equals(logField.value)));
            assertTrue(completion.getKeyValuePairs().stream()
                    .anyMatch(logField -> "path".equals(logField.key) && "/api/chat/citations".equals(logField.value)));
            assertTrue(completion.getKeyValuePairs().stream()
                    .anyMatch(logField -> "requestId".equals(logField.key) && requestId.equals(logField.value)));
            assertTrue(completion.getKeyValuePairs().stream()
                    .anyMatch(logField -> "retryAfter".equals(logField.key) && "12".equals(logField.value)));
            assertTrue(completion.getKeyValuePairs().stream()
                    .anyMatch(logField -> "durationMs".equals(logField.key)
                            && logField.value instanceof Long durationMillis
                            && durationMillis >= 0));
        }
    }

    @Test
    void recordsANestedErrorDispatchOnlyOnce() throws ServletException, IOException {
        ApiRequestLoggingFilter requestLogging = new ApiRequestLoggingFilter();
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/chat/citations");
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(ApiRequestLoggingFilter.class))) {
            requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> {
                ((HttpServletResponse) servletResponse).sendError(503);
                apiResponse.setCommitted(false);
                apiRequest.setDispatcherType(DispatcherType.ERROR);
                apiRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/chat/citations");
                apiRequest.setRequestURI("/error");
                requestLogging.doFilter(
                        apiRequest, apiResponse, (errorRequest, errorResponse) -> ((HttpServletResponse) errorResponse)
                                .setStatus(502));
                assertTrue(completedRequests(capturedLogs).isEmpty());
                apiRequest.setDispatcherType(DispatcherType.REQUEST);
                ((HttpServletResponse) servletResponse).setStatus(500);
            });
            assertEquals(1, completedRequests(capturedLogs).size());
            assertEquals(500, apiResponse.getStatus());
            assertTrue(completedRequests(capturedLogs).getFirst().getKeyValuePairs().stream()
                    .anyMatch(logField -> "status".equals(logField.key)
                            && Integer.valueOf(500).equals(logField.value)));
        }
    }

    @Test
    void recordsARegularResponseOnceWithoutAnErrorDispatch() throws ServletException, IOException {
        ApiRequestLoggingFilter requestLogging = new ApiRequestLoggingFilter();
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/chat/citations");
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        try (ExpectedLogEvents capturedLogs =
                ExpectedLogEvents.capture((Logger) LoggerFactory.getLogger(ApiRequestLoggingFilter.class))) {
            requestLogging.doFilter(apiRequest, apiResponse, (servletRequest, servletResponse) -> ((HttpServletResponse)
                            servletResponse)
                    .setStatus(204));
            assertFalse(apiRequest.isAsyncStarted());
            assertEquals(1, completedRequests(capturedLogs).size());
            assertEquals(Level.INFO, completedRequests(capturedLogs).getFirst().getLevel());
            assertTrue(completedRequests(capturedLogs).getFirst().getKeyValuePairs().stream()
                    .anyMatch(logField -> "status".equals(logField.key)
                            && Integer.valueOf(204).equals(logField.value)));
        }
    }

    private static List<ILoggingEvent> completedRequests(ExpectedLogEvents capturedLogs) {
        return capturedLogs.events().stream()
                .filter(logEvent -> "API request completed".equals(logEvent.getFormattedMessage()))
                .toList();
    }
}
