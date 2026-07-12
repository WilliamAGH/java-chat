package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ExtendedModelMap;

/** Verifies error diagnostics retain request attribution without exposing query data. */
@WebMvcTest(controllers = CustomErrorController.class)
@Import({com.williamcallahan.javachat.config.AppProperties.class, ExceptionResponseBuilder.class})
@WithMockUser
class CustomErrorControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomErrorController controller;

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(CustomErrorController.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void captureControllerLogs() {
        logAppender.start();
        controllerLogger.addAppender(logAppender);
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogger.detachAppender(logAppender);
        logAppender.stop();
        logAppender.list.clear();
    }

    @Test
    void logs_non_api_not_found_at_info_with_safe_request_metadata() throws Exception {
        mvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/missing.js?token=secret")
                        .header("User-Agent", "browser")
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "default\r\nServlet"))
                .andExpect(status().isNotFound());

        ILoggingEvent event = onlyLogEvent();
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("status=404 source=default??Servlet method=GET"));
        assertTrue(event.getFormattedMessage().contains("uri=/missing.js host=localhost"));
        assertTrue(event.getFormattedMessage().contains("userAgent=browser requestId="));
        assertFalse(event.getFormattedMessage().contains("secret"));
        assertNull(event.getThrowableProxy());
    }

    @Test
    void logs_unknown_api_path_at_warn() throws Exception {
        mvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/api/unknown")
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet"))
                .andExpect(status().isNotFound());

        ILoggingEvent event = onlyLogEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("status=404 source=dispatcherServlet method=GET"));
        assertTrue(event.getFormattedMessage().contains("uri=/api/unknown host=localhost"));
    }

    @Test
    void logs_server_error_at_error_with_exception() throws Exception {
        IllegalStateException failure = new IllegalStateException("dependency failed");

        mvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/chat")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, failure)
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent event = onlyLogEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
    }

    @Test
    void logs_server_error_without_exception_at_error() throws Exception {
        mvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/chat"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent event = onlyLogEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertNull(event.getThrowableProxy());
    }

    @Test
    void returns_the_same_non_empty_servlet_request_id_that_it_logs() {
        String expectedRequestId = "servlet-request-123";
        MockHttpServletRequest servletRequest = new MockHttpServletRequest() {
            @Override
            public String getRequestId() {
                return expectedRequestId;
            }
        };
        servletRequest.setMethod("PATCH");
        servletRequest.setServerName("localhost");
        servletRequest.addHeader("User-Agent", "browser");
        servletRequest.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value());
        servletRequest.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Missing");
        servletRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/unknown");
        servletRequest.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        controller.handleError(servletRequest, servletResponse, new ExtendedModelMap());

        assertEquals(expectedRequestId, servletResponse.getHeader("X-Request-ID"));
        String message = onlyLogEvent().getFormattedMessage();
        assertTrue(message.contains("method=PATCH uri=/api/unknown"));
        assertTrue(message.contains("requestId=" + expectedRequestId));
    }

    @Test
    void bounds_long_fields_and_marks_missing_fields_unknown() throws Exception {
        String longUserAgent = "a".repeat(600);
        mvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/api/unknown").header("User-Agent", longUserAgent))
                .andExpect(status().isNotFound());

        String message = onlyLogEvent().getFormattedMessage();
        assertTrue(message.contains("source=unknown"));
        assertTrue(message.contains("userAgent=" + "a".repeat(512) + " requestId="));
        assertFalse(message.contains("a".repeat(513)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder errorRequest(
            HttpStatus status, String uri) {
        return get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status.value())
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, status == HttpStatus.NOT_FOUND ? "Missing" : "Failure")
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, uri);
    }

    private ILoggingEvent onlyLogEvent() {
        List<ILoggingEvent> events = logAppender.list;
        assertEquals(1, events.size());
        return events.getFirst();
    }
}
