package com.williamcallahan.javachat.application.contact;

/**
 * Signals that a client IP exhausted its hourly contact-submission allowance.
 *
 * <p>Typed so the web layer can map exactly this condition to 429 without
 * pattern-matching on message text.</p>
 */
public class ContactRateLimitExceededException extends RuntimeException {
    private static final String RATE_LIMIT_EXCEEDED_MESSAGE = "Too many contact submissions; please try again later";

    /**
     * Creates the exception with a client-safe, IP-free message.
     */
    public ContactRateLimitExceededException() {
        super(RATE_LIMIT_EXCEEDED_MESSAGE);
    }
}
