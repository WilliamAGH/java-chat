package com.williamcallahan.javachat.application.contact;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.williamcallahan.javachat.config.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Delivers validated contact-form submissions to the site owner over self-hosted SMTP.
 *
 * <p>Owns every spam guard for the public form: the honeypot field, the render-time trap,
 * and a fixed-window per-IP allowance backed by an expiring Caffeine cache. Spam submissions
 * are dropped silently (the caller still answers 202 so bots learn nothing), while genuine
 * mail failures propagate so the caller never reports success for an unsent message.</p>
 */
@Service
public class ContactSubmissionUseCase {
    private static final Logger log = LoggerFactory.getLogger(ContactSubmissionUseCase.class);

    /**
     * Minimum age of a rendered form at submission time. Any younger submission is a bot
     * filling the form programmatically; a render timestamp in the future yields a negative
     * age and is covered by the same bound, so no separate clock-skew rule is needed.
     */
    private static final long MIN_SUBMISSION_AGE_MILLIS = 3_000L;

    /** Accepted submissions allowed per client IP within one fixed window. */
    private static final int MAX_ACCEPTED_SUBMISSIONS_PER_IP = 3;

    /** Fixed window length; anchored at an IP's first accepted submission after expiry. */
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofHours(1);

    private static final String SUBJECT_PREFIX = "Java Chat contact: ";

    private final JavaMailSender javaMailSender;
    private final AppProperties appProperties;
    private final Cache<String, AtomicInteger> acceptedSubmissionsPerIp;

    /**
     * Creates the use case backed by the Spring-managed mail sender and contact routing config.
     */
    public ContactSubmissionUseCase(JavaMailSender javaMailSender, AppProperties appProperties) {
        this.javaMailSender = javaMailSender;
        this.appProperties = appProperties;
        this.acceptedSubmissionsPerIp =
                Caffeine.newBuilder().expireAfterWrite(RATE_LIMIT_WINDOW).build();
    }

    /**
     * Delivers one submission, silently dropping spam and rejecting rate-limited clients.
     *
     * @param contactSubmission validated submission from the web boundary
     * @throws ContactRateLimitExceededException when the client IP exhausted its hourly allowance
     * @throws org.springframework.mail.MailException when the message cannot be delivered
     */
    public void submit(ContactSubmission contactSubmission) {
        if (isSpamSubmission(contactSubmission)) {
            log.info(
                    "Dropping contact submission flagged as spam (remoteAddress={})",
                    contactSubmission.remoteAddress());
            return;
        }

        AtomicInteger acceptedSubmissionCount =
                acceptedSubmissionsPerIp.get(contactSubmission.remoteAddress(), remoteAddress -> new AtomicInteger());
        if (acceptedSubmissionCount.get() >= MAX_ACCEPTED_SUBMISSIONS_PER_IP) {
            log.info("Contact submission rate limited (remoteAddress={})", contactSubmission.remoteAddress());
            throw new ContactRateLimitExceededException();
        }

        javaMailSender.send(buildMimeMessage(contactSubmission));
        acceptedSubmissionCount.incrementAndGet();
        log.info("Contact message delivered (remoteAddress={})", contactSubmission.remoteAddress());
    }

    private static boolean isSpamSubmission(ContactSubmission contactSubmission) {
        if (contactSubmission.hasHoneypotContent()) {
            return true;
        }
        Long renderedAt = contactSubmission.renderedAt();
        if (renderedAt == null) {
            return true;
        }
        long submissionAgeMillis = contactSubmission.receivedAt().toEpochMilli() - renderedAt.longValue();
        return submissionAgeMillis < MIN_SUBMISSION_AGE_MILLIS;
    }

    private MimeMessage buildMimeMessage(ContactSubmission contactSubmission) {
        AppProperties.Contact contactProperties = appProperties.getContact();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage, StandardCharsets.UTF_8.name());
            messageHelper.setFrom(contactProperties.getSenderEmail());
            messageHelper.setTo(contactProperties.getRecipientEmail());
            messageHelper.setReplyTo(contactSubmission.email());
            messageHelper.setSubject(SUBJECT_PREFIX + contactSubmission.name());
            messageHelper.setText(buildPlainTextBody(contactSubmission), false);
        } catch (MessagingException messageBuildFailure) {
            throw new MailParseException(messageBuildFailure);
        }
        return mimeMessage;
    }

    private static String buildPlainTextBody(ContactSubmission contactSubmission) {
        return "Name: "
                + contactSubmission.name() + "\nEmail: "
                + contactSubmission.email() + "\nRemote IP: "
                + contactSubmission.remoteAddress() + "\nReceived at: "
                + contactSubmission.receivedAt() + "\n\nMessage:\n"
                + contactSubmission.message();
    }
}
