package com.williamcallahan.javachat.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies browser error pages are available only to servlet error dispatches. */
@WebMvcTest(controllers = ErrorDocumentationController.class)
@Import(com.williamcallahan.javachat.config.AppProperties.class)
@WithMockUser
class ErrorDocumentationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ErrorDocumentationController errorDocumentationController;

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(ErrorDocumentationController.class);
    private ExpectedLogEvents controllerLogEvents;

    @BeforeEach
    void captureControllerLogs() {
        controllerLogEvents = ExpectedLogEvents.capture(controllerLogger);
    }

    @AfterEach
    void stopCapturingControllerLogs() {
        controllerLogEvents.close();
    }

    @Test
    void does_not_publish_error_catalog_directly() throws Exception {
        List<String> directCatalogPaths = List.of(
                "/errors",
                "/errors/",
                "/errors/not-found",
                "/errors/access-denied",
                "/errors/not-found.html",
                "/errors/access-denied.html");

        for (String directCatalogPath : directCatalogPaths) {
            mockMvc.perform(get(directCatalogPath).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        }
    }

    @Test
    void preserves_original_status_for_forwarded_error_documentation() throws Exception {
        mockMvc.perform(get("/errors/not-found")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Not Found")));
    }

    @Test
    void rejects_invalid_error_type_slug() throws Exception {
        mockMvc.perform(get("/errors/Not-Found").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }

    @Test
    void returns_not_found_for_missing_error_documentation() throws Exception {
        mockMvc.perform(get("/errors/missing-documentation")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logs_documentation_read_failure_without_resource_path_argument() {
        ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new UnreadableResourceClassLoader(originalContextClassLoader));
            MockHttpServletRequest errorDispatchRequest = new MockHttpServletRequest();
            errorDispatchRequest.setAttribute(
                    RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.INTERNAL_SERVER_ERROR.value());

            assertEquals(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    errorDocumentationController
                            .errorType("not-found", errorDispatchRequest)
                            .getStatusCode());
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        }

        ILoggingEvent documentationReadFailure = onlyControllerLog();
        assertEquals(Level.ERROR, documentationReadFailure.getLevel());
        assertNull(documentationReadFailure.getArgumentArray());
        assertNotNull(documentationReadFailure.getThrowableProxy());
        assertEquals(
                IOException.class.getName(),
                documentationReadFailure.getThrowableProxy().getClassName());
    }

    private ILoggingEvent onlyControllerLog() {
        assertEquals(1, controllerLogEvents.events().size());
        return controllerLogEvents.events().getFirst();
    }

    /** Forces classpath resource reads to fail after discovery succeeds. */
    private static final class UnreadableResourceClassLoader extends ClassLoader {
        private UnreadableResourceClassLoader(ClassLoader parentClassLoader) {
            super(parentClassLoader);
        }

        @Override
        public URL getResource(String resourcePath) {
            return ErrorDocumentationControllerTest.class.getResource(
                    ErrorDocumentationControllerTest.class.getSimpleName() + ".class");
        }

        @Override
        public InputStream getResourceAsStream(String resourcePath) {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException();
                }
            };
        }
    }
}
