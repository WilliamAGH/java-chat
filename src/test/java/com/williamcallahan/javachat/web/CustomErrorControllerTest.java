package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import com.williamcallahan.javachat.service.OpenAiStreamingFailureException;
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

    private static final String CONSOLE_APPENDER_NAME = "CONSOLE";
    private static final String LONG_REQUEST_URI_CHARACTER = "a";
    private static final int LONG_REQUEST_URI_CHARACTER_COUNT = 1_024;
    private static final String LONG_REQUEST_URI_PREFIX = "/api/";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CustomErrorController errorController;

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
        mockMvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/missing.js?token=secret")
                        .header("User-Agent", "spoofed-browser")
                        .with(servletRequest -> {
                            servletRequest.setServerName("spoofed-host");
                            return servletRequest;
                        })
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "default\r\nServlet"))
                .andExpect(status().isNotFound());

        ILoggingEvent requestFailureLog = onlyLogEvent();
        assertEquals(Level.INFO, requestFailureLog.getLevel());
        String diagnostic = requestFailureLog.getFormattedMessage();
        assertTrue(diagnostic.contains("status=404 source=default??Servlet method=GET"));
        assertTrue(diagnostic.contains("uri=/missing.js host=spoofed-host"));
        assertTrue(diagnostic.contains("userAgent=spoofed-browser requestId="));
        assertFalse(diagnostic.contains("secret"));
        List<?> structuredLogFields = requestFailureLog.getKeyValuePairs();
        assertTrue(structuredLogFields == null || structuredLogFields.isEmpty());
        assertNull(requestFailureLog.getThrowableProxy());
    }

    @Test
    void logs_unknown_api_path_at_warn() throws Exception {
        mockMvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/api/unknown")
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet"))
                .andExpect(status().isNotFound());

        ILoggingEvent requestFailureLog = onlyLogEvent();
        assertEquals(Level.WARN, requestFailureLog.getLevel());
        assertTrue(requestFailureLog
                .getFormattedMessage()
                .contains("status=404 source=dispatcherServlet method=GET uri=/api/unknown"));
    }

    @Test
    void renders_unquoted_request_failure_fields_in_console_output() throws Exception {
        mockMvc.perform(errorRequest(HttpStatus.NOT_FOUND, "/api/unknown")
                        .requestAttr(
                                RequestDispatcher.ERROR_SERVLET_NAME,
                                "dispatcherServlet\" forged=\"true\\path\u2028next"))
                .andExpect(status().isNotFound());

        String renderedRequestFailure = consolePatternEncoder().getLayout().doLayout(onlyLogEvent());

        assertTrue(renderedRequestFailure.contains("Request failed status=" + HttpStatus.NOT_FOUND.value()));
        assertTrue(renderedRequestFailure.contains("source=dispatcherServlet? forged=?true?path?next"));
        assertFalse(renderedRequestFailure.contains("forged=\"true\""));
        assertFalse(renderedRequestFailure.contains("\u2028"));
        assertTrue(renderedRequestFailure.contains("method=GET"));
        assertTrue(renderedRequestFailure.contains("uri=/api/unknown"));
        assertTrue(renderedRequestFailure.contains("host=localhost"));
        assertTrue(renderedRequestFailure.contains("userAgent=unknown"));
        assertFalse(renderedRequestFailure.contains("status=\""));
    }

    @Test
    void does_not_expose_servlet_error_message_for_api_requests() throws Exception {
        String servletSecret = "OPENAI_API_KEY=secret-value";

        mockMvc.perform(errorRequest(HttpStatus.BAD_REQUEST, "/api/chat")
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, servletSecret))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                .andExpect(content().string(not(containsString(servletSecret))));
    }

    @Test
    void returns_stable_api_error_when_servlet_throwable_is_not_an_exception() throws Exception {
        AssertionError nonExceptionFailure = new AssertionError("fatal servlet failure");

        mockMvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/api/chat")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, nonExceptionFailure))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()));
    }

    @Test
    void logs_server_error_at_error_with_exception() throws Exception {
        IllegalStateException failure = new IllegalStateException("dependency failed");

        mockMvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/chat")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, failure)
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent requestFailureLog = onlyLogEvent();
        assertEquals(Level.ERROR, requestFailureLog.getLevel());
        assertEquals(
                failure.getClass().getName(),
                requestFailureLog.getThrowableProxy().getClassName());
    }

    @Test
    void logsAlreadyReportedTerminalStreamingFailureAtWarnWithoutException() throws Exception {
        OpenAiStreamingFailureException terminalFailure = mock(OpenAiStreamingFailureException.class);
        IllegalStateException dispatchFailure = new IllegalStateException("dispatch failed", terminalFailure);

        mockMvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/api/chat")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, dispatchFailure)
                        .requestAttr(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent requestFailureLog = onlyLogEvent();
        assertEquals(Level.WARN, requestFailureLog.getLevel());
        assertNull(requestFailureLog.getThrowableProxy());
    }

    @Test
    void logs_server_error_without_exception_at_error() throws Exception {
        mockMvc.perform(errorRequest(HttpStatus.INTERNAL_SERVER_ERROR, "/chat"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent requestFailureLog = onlyLogEvent();
        assertEquals(Level.ERROR, requestFailureLog.getLevel());
        assertNull(requestFailureLog.getThrowableProxy());
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
        servletRequest.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value());
        servletRequest.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Missing");
        servletRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/unknown");
        servletRequest.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, "dispatcherServlet");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        errorController.handleError(servletRequest, servletResponse, new ExtendedModelMap());

        assertEquals(expectedRequestId, servletResponse.getHeader("X-Request-ID"));
        String diagnostic = onlyLogEvent().getFormattedMessage();
        assertTrue(diagnostic.contains("method=PATCH uri=/api/unknown"));
        assertTrue(diagnostic.contains("requestId=" + expectedRequestId));
    }

    @Test
    void bounds_long_uri_and_marks_missing_source_unknown() throws Exception {
        String longRequestUri =
                LONG_REQUEST_URI_PREFIX + LONG_REQUEST_URI_CHARACTER.repeat(LONG_REQUEST_URI_CHARACTER_COUNT);
        mockMvc.perform(errorRequest(HttpStatus.NOT_FOUND, longRequestUri)).andExpect(status().isNotFound());

        String diagnostic = onlyLogEvent().getFormattedMessage();
        assertTrue(diagnostic.contains("source=unknown"));
        assertTrue(diagnostic.contains("uri=" + longRequestUri.substring(0, 512)));
        assertFalse(diagnostic.contains(longRequestUri.substring(0, 513)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder errorRequest(
            HttpStatus status, String uri) {
        return get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status.value())
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, status == HttpStatus.NOT_FOUND ? "Missing" : "Failure")
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, uri);
    }

    private ILoggingEvent onlyLogEvent() {
        List<ILoggingEvent> capturedLogs = logAppender.list;
        assertEquals(1, capturedLogs.size());
        return capturedLogs.getFirst();
    }

    private PatternLayoutEncoder consolePatternEncoder() {
        Appender<ILoggingEvent> rootConsoleAppender = controllerLogger
                .getLoggerContext()
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                .getAppender(CONSOLE_APPENDER_NAME);
        if (!(rootConsoleAppender instanceof ConsoleAppender<?> consoleAppender)) {
            throw new AssertionError("Root logger must use the configured console appender");
        }
        if (!(consoleAppender.getEncoder() instanceof PatternLayoutEncoder patternEncoder)) {
            throw new AssertionError("Console appender must use a pattern layout encoder");
        }
        if (patternEncoder.getLayout() == null) {
            throw new AssertionError("Console pattern layout must be initialized");
        }
        return patternEncoder;
    }
}
