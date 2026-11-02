package com.williamcallahan.javachat.adapters.in.web;

import com.williamcallahan.javachat.support.StructuredLogValue;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlates API requests, including security rejections and asynchronous streaming completion. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApiRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);
    private static final String REQUEST_STARTED_ATTRIBUTE = ApiRequestLoggingFilter.class.getName() + ".started";
    private static final String REQUEST_COMPLETED_ATTRIBUTE = ApiRequestLoggingFilter.class.getName() + ".completed";
    private static final String ERROR_PENDING_ATTRIBUTE = ApiRequestLoggingFilter.class.getName() + ".errorPending";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final int MAX_REQUEST_LOG_FIELD_LENGTH = 256;

    /** Excludes static assets and actuator scrapes from application request records. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest apiRequest) {
        return !requestPath(apiRequest).startsWith("/api/");
    }

    /** Records an escaping exception only after the container establishes its error response. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** Includes the completion dispatch rather than timing only servlet setup. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** Issues a browser-visible ID before security can reject a request. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest apiRequest, HttpServletResponse apiResponse, FilterChain apiFilterChain)
            throws ServletException, IOException {
        String requestId = apiRequest.getRequestId();
        String requestPath = StructuredLogValue.bounded(requestPath(apiRequest), MAX_REQUEST_LOG_FIELD_LENGTH)
                .text();
        if (apiRequest.getAttribute(REQUEST_STARTED_ATTRIBUTE) == null) {
            apiRequest.setAttribute(REQUEST_STARTED_ATTRIBUTE, System.nanoTime());
            apiResponse.setHeader(REQUEST_ID_HEADER, requestId);
            log.atInfo()
                    .setMessage("API request started")
                    .addKeyValue("event", "api_request_started")
                    .addKeyValue("requestId", requestId)
                    .addKeyValue(
                            "method",
                            StructuredLogValue.bounded(apiRequest.getMethod(), MAX_REQUEST_LOG_FIELD_LENGTH)
                                    .text())
                    .addKeyValue("path", requestPath)
                    .log();
        }
        boolean errorDispatch = apiRequest.getDispatcherType() == DispatcherType.ERROR;
        apiFilterChain.doFilter(apiRequest, new ErrorDispatchResponse(apiRequest, apiResponse));
        // sendError returns before the container renders its error page, including security denials.
        // Spring MVC performs a final ASYNC dispatch after an SSE emitter completes or fails.
        if (!apiRequest.isAsyncStarted()
                && (errorDispatch || apiRequest.getAttribute(ERROR_PENDING_ATTRIBUTE) == null)
                && apiRequest.getAttribute(REQUEST_COMPLETED_ATTRIBUTE) == null) {
            apiRequest.setAttribute(REQUEST_COMPLETED_ATTRIBUTE, Boolean.TRUE);
            long startedNanos = (Long) apiRequest.getAttribute(REQUEST_STARTED_ATTRIBUTE);
            log.atLevel(apiResponse.getStatus() >= HttpStatus.BAD_REQUEST.value() ? Level.WARN : Level.INFO)
                    .setMessage("API request completed")
                    .addKeyValue("event", "api_request_completed")
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("method", apiRequest.getMethod())
                    .addKeyValue("path", requestPath)
                    .addKeyValue("status", apiResponse.getStatus())
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
                    .addKeyValue(
                            "retryAfter",
                            StructuredLogValue.bounded(
                                            apiResponse.getHeader("Retry-After"), MAX_REQUEST_LOG_FIELD_LENGTH)
                                    .text())
                    .log();
        }
    }

    /** Retains the same completion ownership when the container nests its error page dispatch. */
    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest apiRequest, HttpServletResponse apiResponse, FilterChain apiFilterChain)
            throws ServletException, IOException {
        apiFilterChain.doFilter(apiRequest, apiResponse);
        // The enclosing dispatch owns completion, after every outer filter has returned.
        apiRequest.removeAttribute(ERROR_PENDING_ATTRIBUTE);
    }

    private static final class ErrorDispatchResponse extends HttpServletResponseWrapper {
        private final HttpServletRequest apiRequest;

        private ErrorDispatchResponse(HttpServletRequest apiRequest, HttpServletResponse apiResponse) {
            super(apiResponse);
            this.apiRequest = apiRequest;
        }

        /** Defers completion until the container renders the error response. */
        @Override
        public void sendError(int status) throws IOException {
            apiRequest.setAttribute(ERROR_PENDING_ATTRIBUTE, Boolean.TRUE);
            super.sendError(status);
        }

        /** Defers completion for error responses carrying a container-rendered message. */
        @Override
        public void sendError(int status, String message) throws IOException {
            apiRequest.setAttribute(ERROR_PENDING_ATTRIBUTE, Boolean.TRUE);
            super.sendError(status, message);
        }
    }

    private static String requestPath(HttpServletRequest apiRequest) {
        Object failedPath = apiRequest.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return failedPath instanceof String failedRequestPath ? failedRequestPath : apiRequest.getRequestURI();
    }
}
