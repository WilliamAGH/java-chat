package com.williamcallahan.javachat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Hooks;

import java.io.InterruptedIOException;
import java.util.Locale;

/**
 * Configures Reactor hooks to handle dropped errors gracefully.
 *
 * <p>When blocking calls on {@code boundedElastic} are cancelled (e.g., via timeout),
 * the thread interruption can cause exceptions that arrive after the Mono completes.
 * Reactor logs these as ERROR by default; this configuration downgrades expected
 * cancellation-related errors to DEBUG.</p>
 */
@Configuration
public class ReactorHooksConfig {

    private static final Logger log = LoggerFactory.getLogger(ReactorHooksConfig.class);

    /**
     * Installs a custom error handler for dropped errors when context refreshes.
     * Uses ContextRefreshedEvent to ensure hook is set on each devtools restart.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void configureDroppedErrorHandler() {
        Hooks.onErrorDropped(error -> {
            if (isExpectedCancellationError(error)) {
                log.debug("Dropped expected cancellation error (exceptionType={})",
                        error.getClass().getSimpleName());
            } else {
                log.warn("Dropped unexpected error", error);
            }
        });
        log.info("Reactor dropped-error hook configured");
    }

    private boolean isExpectedCancellationError(Throwable error) {
        // Direct interruption exceptions
        if (error instanceof InterruptedException || error instanceof InterruptedIOException) {
            return true;
        }
        // Check cause chain for interruption
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) {
                return true;
            }
            cause = cause.getCause();
        }
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("interrupt");
    }
}
