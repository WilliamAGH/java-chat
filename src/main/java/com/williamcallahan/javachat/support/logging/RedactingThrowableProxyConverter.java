package com.williamcallahan.javachat.support.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.util.regex.Pattern;

/**
 * Renders logged throwables with Clerk API-key identifiers redacted.
 *
 * <p>Spring's {@code RestClient} builds a {@code ResourceAccessException} whose
 * message embeds the resolved request URI, so a transport-layer failure against
 * {@code /v1/api_keys/{apiKeyId}/revoke} carries the key identifier into the
 * {@code Caused by} frame that {@code log.error(message, throwable)} renders.
 * Registering this converter as the throwable rendering word (for example
 * {@code %redactedEx}) keeps the full stack trace, every {@code Caused by}
 * line, and all frames intact while replacing each {@code ak_} token with
 * {@code ak_***}.
 *
 * <p>The {@code ak_} prefix is specific to Clerk keys and the redaction pattern
 * covers both the non-secret identifier ({@code ak_<id>}) and the secret token
 * form ({@code ak_secret_<material>}), so the exception type, host, path, and
 * diagnostics remain visible without leaking either value.
 *
 * @see ThrowableProxyConverter
 */
public final class RedactingThrowableProxyConverter extends ThrowableProxyConverter {

    private static final Pattern CLERK_API_KEY_PATTERN = Pattern.compile("ak_[A-Za-z0-9_]+");

    private static final String REDACTED_API_KEY = "ak_***";

    @Override
    protected String throwableProxyToString(IThrowableProxy throwableProxy) {
        String renderedThrowable = super.throwableProxyToString(throwableProxy);
        return CLERK_API_KEY_PATTERN.matcher(renderedThrowable).replaceAll(REDACTED_API_KEY);
    }
}
