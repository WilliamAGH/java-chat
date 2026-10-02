package com.williamcallahan.javachat.support.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.williamcallahan.javachat.application.auth.ApiKeyOperationUnavailableException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** Verifies {@link RedactingThrowableProxyConverter} redacts Clerk keys while preserving stack diagnostics. */
class RedactingThrowableProxyConverterTest {

    private static final String CLERK_API_KEY_ID = "ak_0123456789abcdef0123456789abcdef";

    private static final String CLERK_API_KEY_SECRET = "ak_secret_0123456789abcdef0123456789abcdef";

    private static final String CLERK_REVOKE_URI = "https://api.clerk.com/v1/api_keys/" + CLERK_API_KEY_ID + "/revoke";

    private static final String REVOCATION_LOG_MESSAGE = "Clerk API key revocation was unavailable";

    @Test
    void redactsClerkApiKeyIdFromRenderedThrowable() {
        ResourceAccessException transportFailure = new ResourceAccessException(
                "I/O error on POST request for \"" + CLERK_REVOKE_URI + "\": Connection refused",
                new ConnectException("Connection refused"));

        String rendered = renderWithRedactingPattern(transportFailure);

        assertFalse(
                rendered.contains(CLERK_API_KEY_ID), "rendered throwable must not leak the api key id: " + rendered);
        assertTrue(rendered.contains("ak_***"), "rendered throwable must replace the id with ak_***: " + rendered);
        assertTrue(
                rendered.contains(ResourceAccessException.class.getName()), "exception type must remain: " + rendered);
        assertTrue(rendered.contains("Connection refused"), "underlying transport message must remain: " + rendered);
        assertTrue(rendered.contains("Caused by:"), "cause chain must remain: " + rendered);
    }

    @Test
    void redactsClerkApiKeySecretFormEntirely() {
        ResourceAccessException transportFailure = new ResourceAccessException(
                "I/O error: " + CLERK_API_KEY_SECRET + " rejected", new ConnectException("x"));

        String rendered = renderWithRedactingPattern(transportFailure);

        assertFalse(rendered.contains(CLERK_API_KEY_SECRET), "secret form must not appear: " + rendered);
        assertTrue(rendered.contains("ak_***"), "secret form must be redacted to ak_***: " + rendered);
    }

    @Test
    void preservesStackTraceAndCausedByChain() {
        ApiKeyOperationUnavailableException revocationFailure = new ApiKeyOperationUnavailableException(
                "Clerk API key revocation failed",
                new ResourceAccessException(
                        "I/O error on POST request for \"" + CLERK_REVOKE_URI + "\": Connection refused",
                        new ConnectException("Connection refused")));

        String rendered = renderWithRedactingPattern(revocationFailure);

        assertTrue(
                rendered.contains("ApiKeyOperationUnavailableException: Clerk API key revocation failed"),
                "outer exception line must remain: " + rendered);
        assertTrue(
                rendered.contains("Caused by: org.springframework.web.client.ResourceAccessException"),
                "wrapping cause must remain: " + rendered);
        assertTrue(
                rendered.contains("Caused by: java.net.ConnectException: Connection refused"),
                "root cause must remain: " + rendered);
        assertFalse(rendered.contains(CLERK_API_KEY_ID), "id must be redacted across the whole chain: " + rendered);
        assertFalse(rendered.contains(CLERK_API_KEY_SECRET), "secret must be redacted: " + rendered);
    }

    @Test
    void rendersCausedByExactlyOnceWithoutDoubleAppend() {
        Exception failure = new RuntimeException("boom " + CLERK_API_KEY_ID, new IllegalStateException("root cause"));

        String rendered = renderWithRedactingPattern(failure);

        long causedByLines =
                rendered.lines().filter(line -> line.startsWith("Caused by:")).count();
        assertEquals(
                1, causedByLines, "exactly one Caused by line; a second means Logback double-appended: " + rendered);
        assertFalse(rendered.contains(CLERK_API_KEY_ID), "id must be redacted: " + rendered);
    }

    private String renderWithRedactingPattern(Throwable throwable) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger(RedactingThrowableProxyConverterTest.class.getName());
        LoggingEvent event =
                new LoggingEvent(Logger.FQCN, logger, Level.ERROR, REVOCATION_LOG_MESSAGE, throwable, null);
        PatternLayout layout = new PatternLayout();
        layout.getInstanceConverterMap().put("redactedEx", RedactingThrowableProxyConverter::new);
        layout.setContext(context);
        layout.setPattern("%msg%n%redactedEx");
        layout.start();
        return layout.doLayout(event);
    }
}
