package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

/**
 * Verifies fail-fast SMTP credential validation for the contact form in production.
 */
class RequiredMailCredentialValidationTest {
    private static final String MISSING_CREDENTIAL_MESSAGE_FRAGMENT = "SPRING_MAIL_USERNAME";

    @Test
    void missingSmtpCredentials_throwsIllegalStateException() {
        RequiredMailCredentialValidation validation = createValidation(null, null);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, validation::validateRequiredMailCredential);
        assertTrue(thrown.getMessage().contains(MISSING_CREDENTIAL_MESSAGE_FRAGMENT));
    }

    @Test
    void blankSmtpPassword_throwsIllegalStateException() {
        RequiredMailCredentialValidation validation = createValidation("smtp-user", "  ");

        assertThrows(IllegalStateException.class, validation::validateRequiredMailCredential);
    }

    @Test
    void presentSmtpCredentials_passes() {
        RequiredMailCredentialValidation validation = createValidation("smtp-user", "smtp-password");

        assertDoesNotThrow(validation::validateRequiredMailCredential);
    }

    @Test
    void validationRemainsEagerWhenApplicationBeansAreLazy() {
        Lazy lazyConfiguration = RequiredMailCredentialValidation.class.getAnnotation(Lazy.class);

        assertNotNull(lazyConfiguration);
        assertFalse(lazyConfiguration.value());
    }

    @Test
    void validationIsLimitedToWebApplications() {
        ConditionalOnWebApplication webApplicationCondition =
                RequiredMailCredentialValidation.class.getAnnotation(ConditionalOnWebApplication.class);

        assertNotNull(webApplicationCondition);
    }

    @Test
    void validationIsLimitedToTheProdProfile() {
        Profile profileRestriction = RequiredMailCredentialValidation.class.getAnnotation(Profile.class);

        assertNotNull(profileRestriction);
        assertEquals(List.of("prod"), List.of(profileRestriction.value()));
    }

    private static RequiredMailCredentialValidation createValidation(String smtpUsername, String smtpPassword) {
        MailProperties mailProperties = new MailProperties();
        mailProperties.setUsername(smtpUsername);
        mailProperties.setPassword(smtpPassword);
        return new RequiredMailCredentialValidation(mailProperties);
    }
}
