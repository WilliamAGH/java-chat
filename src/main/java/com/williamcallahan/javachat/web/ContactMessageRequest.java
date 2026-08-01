package com.williamcallahan.javachat.web;

/**
 * Request body for the public contact/support form.
 *
 * <p>Carries raw, unvalidated transport values; {@code ContactSubmission} owns every
 * field invariant at the application boundary. Fields stay nullable here so missing
 * values reach validation (or spam classification) instead of failing deserialization.</p>
 *
 * @param name sender display name (1-100 chars)
 * @param email sender reply address (max 254 chars)
 * @param message support message body (1-5000 chars)
 * @param website honeypot field; legitimate clients always send an empty string
 * @param renderedAt epoch milliseconds when the form was rendered; missing means spam
 */
public record ContactMessageRequest(String name, String email, String message, String website, Long renderedAt) {}
