package com.williamcallahan.javachat.application.contact;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import java.util.Objects;

/**
 * One validated contact-form submission as it crosses the web boundary into the application layer.
 *
 * <p>The compact constructor owns every field invariant (length bounds, email syntax, header-injection
 * rejection) so downstream mail construction never sees an untrusted value. The honeypot field and
 * render timestamp travel alongside the message because spam classification happens in the use case,
 * not in the controller.</p>
 *
 * @param name sender display name; also feeds the mail subject, so line breaks are rejected
 * @param email sender reply address, RFC 822 validated per jakarta.mail
 * @param message support message body
 * @param website honeypot field; legitimate clients always submit an empty string
 * @param renderedAt epoch milliseconds when the form was rendered; null when the client omitted it
 * @param remoteAddress client IP as seen by the servlet container (proxy-correct via forward headers)
 * @param receivedAt server-side receipt instant recorded by the controller
 */
public record ContactSubmission(
        String name,
        String email,
        String message,
        String website,
        Long renderedAt,
        String remoteAddress,
        Instant receivedAt) {

    /** Maximum accepted name length per the public API contract. */
    public static final int MAX_NAME_LENGTH = 100;
    /** Maximum accepted email length per RFC 5321 path limits. */
    public static final int MAX_EMAIL_LENGTH = 254;
    /** Maximum accepted message length per the public API contract. */
    public static final int MAX_MESSAGE_LENGTH = 5000;

    /**
     * Creates a submission with all field invariants enforced.
     *
     * @throws IllegalArgumentException when name, email, or message violate the contract bounds
     */
    public ContactSubmission {
        name = requireValidName(name);
        email = requireValidEmail(email);
        message = requireValidMessage(message);
        website = website == null ? "" : website;
        Objects.requireNonNull(remoteAddress, "remoteAddress must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }

    /**
     * Reports whether the honeypot field carries bot content.
     *
     * @return true when the submission must be dropped silently as spam
     */
    public boolean hasHoneypotContent() {
        return !website.isBlank();
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        rejectLineBreaks(name, "name");
        return name;
    }

    private static String requireValidEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("email must be at most " + MAX_EMAIL_LENGTH + " characters");
        }
        rejectLineBreaks(email, "email");
        try {
            new InternetAddress(email, true).validate();
        } catch (AddressException addressException) {
            throw new IllegalArgumentException("email must be a valid address", addressException);
        }
        return email;
    }

    private static String requireValidMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        return message;
    }

    private static void rejectLineBreaks(String fieldContent, String fieldName) {
        if (fieldContent.indexOf('\r') >= 0 || fieldContent.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain line breaks");
        }
    }
}
