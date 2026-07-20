package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/** Verifies that command-line startup excludes servlet-only security infrastructure. */
class SecurityConfigNonWebStartupTest {
    private final ApplicationContextRunner applicationContextRunner =
            new ApplicationContextRunner().withUserConfiguration(SecurityConfig.class);

    @Test
    void startsWithoutServletSecurityBeansInNonWebContext() {
        applicationContextRunner.run(applicationContext -> {
            assertNull(applicationContext.getStartupFailure());
            assertTrue(
                    applicationContext.getBeansOfType(SecurityFilterChain.class).isEmpty());
            assertTrue(applicationContext
                    .getBeansOfType(CorsConfigurationSource.class)
                    .isEmpty());
        });
    }
}
