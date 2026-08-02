package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

/**
 * Validates that SMTP credentials are present for the contact form in production.
 *
 * <p>Scoped to the {@code prod} profile so local development and CI boot without a
 * real SMTP account: production is the only environment where a silently unsendable
 * support inbox would lose user messages. Fails fast with a clear error instead of
 * deferring failure to the first {@code POST /api/contact} submission.</p>
 */
@Configuration
@Lazy(false)
@ConditionalOnWebApplication
@Profile("prod")
public class RequiredMailCredentialValidation {
    private static final Logger log = LoggerFactory.getLogger(RequiredMailCredentialValidation.class);
    private static final String MISSING_SMTP_CREDENTIAL_MESSAGE =
            "Java Chat prod requires SMTP credentials for /api/contact."
                    + " Set SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD.";
    private static final String MAIL_CREDENTIAL_VALIDATION_PASSED_MESSAGE =
            "Required mail credential validation passed";

    private final MailProperties mailProperties;

    RequiredMailCredentialValidation(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
    }

    /**
     * Validates the SMTP credential pair and halts prod web startup when it is missing.
     *
     * @throws IllegalStateException if the SMTP username or password is absent or blank
     */
    @PostConstruct
    public void validateRequiredMailCredential() {
        if (isBlank(mailProperties.getUsername()) || isBlank(mailProperties.getPassword())) {
            throw new IllegalStateException(MISSING_SMTP_CREDENTIAL_MESSAGE);
        }

        log.info(MAIL_CREDENTIAL_VALIDATION_PASSED_MESSAGE);
    }

    private static boolean isBlank(String credential) {
        return credential == null || credential.isBlank();
    }
}
