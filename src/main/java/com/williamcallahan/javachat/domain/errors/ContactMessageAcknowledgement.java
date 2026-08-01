package com.williamcallahan.javachat.domain.errors;

import java.util.Objects;

/**
 * Acknowledges a contact-form submission with a fixed 202 payload.
 *
 * <p>The same acknowledgement answers real sends and silent spam drops so bots
 * cannot distinguish acceptance from discard.</p>
 *
 * @param status fixed status indicator ("accepted")
 */
public record ContactMessageAcknowledgement(String status) implements ApiResponse {
    private static final String STATUS_ACCEPTED = "accepted";

    public ContactMessageAcknowledgement {
        Objects.requireNonNull(status, "Status is required");
    }

    /**
     * Creates the single acknowledgement shape shared by sends and spam drops.
     *
     * @return acknowledgement payload with status "accepted"
     */
    public static ContactMessageAcknowledgement accepted() {
        return new ContactMessageAcknowledgement(STATUS_ACCEPTED);
    }
}
