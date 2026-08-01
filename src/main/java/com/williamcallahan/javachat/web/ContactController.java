package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.application.contact.ContactRateLimitExceededException;
import com.williamcallahan.javachat.application.contact.ContactSubmission;
import com.williamcallahan.javachat.application.contact.ContactSubmissionUseCase;
import com.williamcallahan.javachat.domain.errors.ApiResponse;
import com.williamcallahan.javachat.domain.errors.ContactMessageAcknowledgement;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts public contact/support form submissions and routes them to the site owner by email.
 *
 * <p>Public by design (no authentication); abuse resistance lives in the use case
 * (honeypot, render-time trap, per-IP allowance) while Spring Security's cookie CSRF
 * flow already guards the POST. The endpoint answers 202 identically for delivered
 * messages and silently dropped spam so bots learn nothing.</p>
 */
@RestController
@RequestMapping("/api/contact")
@PermitAll
@PreAuthorize("permitAll()")
public class ContactController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    private final ContactSubmissionUseCase contactSubmissionUseCase;

    /**
     * Creates the contact controller backed by the submission use case and shared error response builder.
     */
    public ContactController(
            ContactSubmissionUseCase contactSubmissionUseCase, ExceptionResponseBuilder exceptionBuilder) {
        super(exceptionBuilder);
        this.contactSubmissionUseCase = contactSubmissionUseCase;
    }

    /**
     * Accepts one contact submission for email delivery.
     *
     * @param contactMessageRequest raw form payload from the client
     * @param servletRequest current request; supplies the proxy-correct client IP
     * @return 202 acknowledgement for sends and spam drops, 400 for contract violations,
     *     429 when the client IP is rate limited, 500 when delivery fails
     */
    @PostMapping
    public ResponseEntity<ApiResponse> submitContactMessage(
            @RequestBody ContactMessageRequest contactMessageRequest, HttpServletRequest servletRequest) {
        try {
            ContactSubmission contactSubmission = new ContactSubmission(
                    contactMessageRequest.name(),
                    contactMessageRequest.email(),
                    contactMessageRequest.message(),
                    contactMessageRequest.website(),
                    contactMessageRequest.renderedAt(),
                    servletRequest.getRemoteAddr(),
                    Instant.now());
            contactSubmissionUseCase.submit(contactSubmission);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ContactMessageAcknowledgement.accepted());
        } catch (ContactRateLimitExceededException rateLimitExceededException) {
            return exceptionBuilder.buildErrorResponse(
                    HttpStatus.TOO_MANY_REQUESTS, rateLimitExceededException.getMessage());
        } catch (IllegalArgumentException validationException) {
            return handleValidationException(validationException);
        } catch (MailException mailException) {
            log.error(
                    "Contact mail delivery failed (exception type: {})",
                    mailException.getClass().getSimpleName());
            return handleServiceException(mailException, "send contact message");
        }
    }
}
